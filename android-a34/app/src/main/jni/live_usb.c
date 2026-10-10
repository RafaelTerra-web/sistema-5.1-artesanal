#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <pthread.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <stdio.h>
#include <fcntl.h>

/* A dedicated pump owns every USBFS URB. Encoded input never reaches OUT. */
#define LIVE_QUEUE 4
#define LIVE_PACKETS 4
#define LIVE_INPUT_PACKETS 16
#define LIVE_INPUT_PACKET 200
#define LIVE_OUTPUT_PACKET 576
#define LIVE_INPUT_CAPACITY 131072
#define LIVE_OUTPUT_CAPACITY 55296
#define PCM_FRAME_BYTES 12
#define LIVE_PRIME_BYTES (3072 * PCM_FRAME_BYTES)
struct live_slot {
    struct usbdevfs_urb *urb;
    uint8_t *data;
    int pending, complete;
    uint64_t sequence;
};
struct live_ring { uint8_t *data; size_t capacity, head, size; };
struct live_session {
    int fd, stopping, ready, error, worker_created, muted, priming;
    const char *error_phase;
    pthread_t worker;
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    struct live_slot input[LIVE_QUEUE], output[LIVE_QUEUE];
    struct live_ring carrier, pcm;
    uint64_t input_sequence, input_expected, output_sequence, output_expected;
    uint64_t input_bytes, input_packets, output_bytes, output_packets, output_requested_bytes;
    int output_byte_accounting_known;
    uint64_t silence_frames, underrun_events, input_errors, output_errors, gated_frames, priming_frames, refill_count;
    uint64_t input_gaps, output_gaps;
    int input_frame_seen, output_frame_seen, input_timing_known, output_timing_known;
    int input_next_frame, output_next_frame, input_last_frame, output_last_frame;
    int64_t input_progress_ms, output_progress_ms;
    int last_was_silent;
};
static int64_t live_ms(void) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}
static struct timespec live_deadline(int milliseconds) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    now.tv_sec += milliseconds / 1000;
    now.tv_nsec += (long)(milliseconds % 1000) * 1000000;
    if (now.tv_nsec >= 1000000000) { now.tv_nsec -= 1000000000; now.tv_sec++; }
    return now;
}
static void live_throw(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}
static void live_transport_error(JNIEnv *env,struct live_session *session,const char *prefix) {
    struct usbdevfs_getdriver owner;memset(&owner,0,sizeof(owner));owner.interface=2;
    const char *name="unavailable";
    if(ioctl(session->fd,USBDEVFS_GETDRIVER,&owner)==0){owner.driver[sizeof(owner.driver)-1]=0;name=owner.driver;}
    char detail[256];snprintf(detail,sizeof(detail),"%s; %s; errno=%d (%s); input driver=%.32s",prefix,
        session->error_phase?session->error_phase:"packet validation",session->error,strerror(session->error),name);
    live_throw(env,detail);
}
static void live_ring_put(struct live_ring *ring, const uint8_t *source, size_t bytes) {
    size_t tail = (ring->head + ring->size) % ring->capacity;
    size_t first = ring->capacity - tail; if (first > bytes) first = bytes;
    memcpy(ring->data + tail, source, first);
    memcpy(ring->data, source + first, bytes - first); ring->size += bytes;
}
static void live_ring_get(struct live_ring *ring, uint8_t *destination, size_t bytes) {
    size_t first = ring->capacity - ring->head; if (first > bytes) first = bytes;
    memcpy(destination, ring->data + ring->head, first);
    memcpy(destination + first, ring->data, bytes - first);
    ring->head = (ring->head + bytes) % ring->capacity; ring->size -= bytes;
}
static void live_fail_locked(struct live_session *session, int error) {
    if (!session->error) session->error = error ? error : EIO;
    session->stopping = 1; pthread_cond_broadcast(&session->changed);
}
static int live_allocate_slot(struct live_slot *slot, int endpoint, int width) {
    int packets=endpoint==0x85?LIVE_INPUT_PACKETS:LIVE_PACKETS;
    slot->urb = calloc(1, sizeof(struct usbdevfs_urb) + packets * sizeof(struct usbdevfs_iso_packet_desc));
    slot->data = calloc(packets, (size_t)width);
    if (!slot->urb || !slot->data) return ENOMEM;
    struct usbdevfs_urb *urb = slot->urb;
    urb->type = USBDEVFS_URB_TYPE_ISO; urb->endpoint = (unsigned char)endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP; urb->buffer = slot->data;
    urb->buffer_length = packets * width; urb->number_of_packets = packets;
    urb->usercontext = slot;
    for (int packet = 0; packet < packets; packet++) urb->iso_frame_desc[packet].length = (unsigned int)width;
    return 0;
}
static int live_submit(struct live_session *session, struct live_slot *slot) {
    slot->complete = 0; slot->urb->status = 0; slot->urb->actual_length = 0; slot->urb->error_count = 0;
    for (int packet = 0; packet < slot->urb->number_of_packets; packet++) {
        slot->urb->iso_frame_desc[packet].actual_length = 0;
        slot->urb->iso_frame_desc[packet].status = 0;
    }
    if (ioctl(session->fd, USBDEVFS_SUBMITURB, slot->urb) < 0) {
        session->error_phase=slot->urb->endpoint==0x85?"submit input":"submit output";return errno;
    }
    slot->pending = 1; return 0;
}
/* Called only by the pump while holding the session mutex. */
static void live_fill_output(struct live_session *session, struct live_slot *slot) {
    if (!session->muted && session->priming && session->pcm.size >= LIVE_PRIME_BYTES) session->priming = 0;
    for (int packet = 0; packet < LIVE_PACKETS; packet++) {
        uint8_t *destination = slot->data + packet * LIVE_OUTPUT_PACKET;
        if (!session->muted && session->priming) {
            memset(destination, 0, LIVE_OUTPUT_PACKET);
            session->priming_frames += LIVE_OUTPUT_PACKET / PCM_FRAME_BYTES;
            session->last_was_silent = 0;
            continue;
        }
        if (!session->muted && session->pcm.size < LIVE_OUTPUT_PACKET) {
            /* Keep a short tail in the FIFO; never splice it onto late decoded
               fragments. Refill a bounded64ms before resuming continuous OUT. */
            memset(destination, 0, LIVE_OUTPUT_PACKET);
            session->silence_frames += LIVE_OUTPUT_PACKET / PCM_FRAME_BYTES;
            session->underrun_events++; session->refill_count++; session->priming = 1;
            session->last_was_silent = 1;
            continue;
        }
        size_t bytes = session->pcm.size;
        if (bytes > LIVE_OUTPUT_PACKET) bytes = LIVE_OUTPUT_PACKET;
        bytes -= bytes % PCM_FRAME_BYTES;
        if (bytes) live_ring_get(&session->pcm, destination, bytes);
        if (session->muted) {
            memset(destination, 0, LIVE_OUTPUT_PACKET);
            session->gated_frames += LIVE_OUTPUT_PACKET / PCM_FRAME_BYTES;
            session->last_was_silent = 0;
            continue;
        }
        if (bytes < LIVE_OUTPUT_PACKET) {
            memset(destination + bytes, 0, LIVE_OUTPUT_PACKET - bytes);
            session->silence_frames += (LIVE_OUTPUT_PACKET - bytes) / PCM_FRAME_BYTES;
            if (!session->last_was_silent) session->underrun_events++;
            session->last_was_silent = 1;
        } else session->last_was_silent = 0;
    }
    pthread_cond_broadcast(&session->changed);
}
static int live_consume_input(struct live_session *session, struct live_slot *slot) {
    if (slot->urb->status || slot->urb->error_count) return EIO;
    int start = slot->urb->start_frame;
    if (start < 0 || (session->input_frame_seen && session->input_timing_known && (start & 255) != session->input_next_frame)) {
        session->input_gaps++; return EPIPE;
    }
    if (start != 0) session->input_timing_known = 1;
    session->input_frame_seen = 1; session->input_last_frame = start;
    session->input_next_frame = (start + LIVE_INPUT_PACKETS) & 255;
    session->input_progress_ms = live_ms();
    size_t total = 0;
    for (int packet = 0; packet < LIVE_INPUT_PACKETS; packet++) {
        struct usbdevfs_iso_packet_desc *iso = &slot->urb->iso_frame_desc[packet];
        if (iso->status || iso->actual_length > LIVE_INPUT_PACKET || iso->actual_length % 4) {
            session->input_errors++; return EIO;
        }
        total += iso->actual_length;
    }
    if (total > session->carrier.capacity - session->carrier.size) return ENOBUFS;
    for (int packet = 0; packet < LIVE_INPUT_PACKETS; packet++) {
        unsigned int bytes = slot->urb->iso_frame_desc[packet].actual_length;
        live_ring_put(&session->carrier, slot->data + packet * LIVE_INPUT_PACKET, bytes);
        session->input_bytes += bytes; session->input_packets++;
    }
    session->input_expected++; slot->sequence = session->input_sequence++;
    pthread_cond_broadcast(&session->changed);
    return live_submit(session, slot);
}
static int live_consume_output(struct live_session *session, struct live_slot *slot) {
    if (slot->urb->status || slot->urb->error_count) return EIO;
    int start = slot->urb->start_frame;
    if (start < 0 || (session->output_frame_seen && session->output_timing_known && (start & 255) != session->output_next_frame)) {
        session->output_gaps++; return EPIPE;
    }
    if (start != 0) session->output_timing_known = 1;
    session->output_frame_seen = 1; session->output_last_frame = start;
    session->output_next_frame = (start + LIVE_PACKETS) & 255;
    session->output_progress_ms = live_ms();
    for (int packet = 0; packet < LIVE_PACKETS; packet++) {
        if (slot->urb->iso_frame_desc[packet].status) { session->output_errors++; return EIO; }
        unsigned int actual = slot->urb->iso_frame_desc[packet].actual_length;
        if (actual > LIVE_OUTPUT_PACKET || (actual && actual != LIVE_OUTPUT_PACKET)
                || (!actual && session->output_byte_accounting_known)) { session->output_errors++; return EIO; }
        if (actual) session->output_byte_accounting_known = 1;
        session->output_packets++; session->output_bytes += actual;
        session->output_requested_bytes += slot->urb->iso_frame_desc[packet].length;
    }
    session->output_expected++; slot->sequence = session->output_sequence++;
    live_fill_output(session, slot);
    return live_submit(session, slot);
}
static void live_cancel(struct live_session *session) {
    int pending = 0;
    for (int group = 0; group < 2; group++) for (int index = 0; index < LIVE_QUEUE; index++) {
        struct live_slot *slot = group ? &session->output[index] : &session->input[index];
        if (slot->pending) { ioctl(session->fd, USBDEVFS_DISCARDURB, slot->urb); pending++; }
    }
    int64_t deadline = live_ms() + 1500;
    while (pending && live_ms() < deadline) {
        struct usbdevfs_urb *done = NULL;
        if (ioctl(session->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
            if (errno == EAGAIN || errno == EINTR) { usleep(1000); continue; }
            break;
        }
        for (int group = 0; group < 2; group++) for (int index = 0; index < LIVE_QUEUE; index++) {
            struct live_slot *slot = group ? &session->output[index] : &session->input[index];
            if (slot->pending && slot->urb == done) { slot->pending = 0; pending--; }
        }
    }
    pthread_mutex_lock(&session->mutex);
    if (pending && !session->error) session->error = ETIMEDOUT;
    session->stopping = 1; pthread_cond_broadcast(&session->changed);
    pthread_mutex_unlock(&session->mutex);
}
static void *live_worker(void *opaque) {
    struct live_session *session = opaque;
    int error = 0;
    pthread_mutex_lock(&session->mutex);
    for (int index = 0; index < LIVE_QUEUE && !error; index++) {
        session->input[index].sequence = session->input_sequence++;
        error = live_submit(session, &session->input[index]);
    }
    for (int index = 0; index < LIVE_QUEUE && !error; index++) {
        session->output[index].sequence = session->output_sequence++;
        live_fill_output(session, &session->output[index]);
        error = live_submit(session, &session->output[index]);
    }
    if (error) live_fail_locked(session, error);
    session->input_progress_ms = session->output_progress_ms = live_ms();
    session->ready = 1; pthread_cond_broadcast(&session->changed);
    pthread_mutex_unlock(&session->mutex);
    for (;;) {
        pthread_mutex_lock(&session->mutex);
        int64_t now = live_ms();
        if (!session->stopping && (now - session->input_progress_ms > 100 || now - session->output_progress_ms > 100)){
            session->error_phase=now-session->input_progress_ms>100?"input watchdog":"output watchdog";
            live_fail_locked(session, ETIMEDOUT);
        }
        int stopping = session->stopping; pthread_mutex_unlock(&session->mutex);
        if (stopping) break;
        struct usbdevfs_urb *done = NULL;
        if (ioctl(session->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
            if (errno == EAGAIN || errno == EINTR) { struct pollfd wait = {session->fd, POLLOUT, 0}; poll(&wait, 1, 4); continue; }
            int cause=errno;pthread_mutex_lock(&session->mutex);session->error_phase="reap completion";live_fail_locked(session,cause);pthread_mutex_unlock(&session->mutex);break;
        }
        struct live_slot *slot = NULL; int is_output = 0;
        for (int index = 0; index < LIVE_QUEUE; index++) {
            if (session->input[index].urb == done) slot = &session->input[index];
            if (session->output[index].urb == done) { slot = &session->output[index]; is_output = 1; }
        }
        pthread_mutex_lock(&session->mutex);
        if (!slot || !slot->pending) { live_fail_locked(session, EIO); pthread_mutex_unlock(&session->mutex); break; }
        slot->pending = 0; slot->complete = 1;
        error = 0;
        if (is_output) {
            for (;;) {
                struct live_slot *next = NULL;
                for (int index = 0; index < LIVE_QUEUE; index++)
                    if (session->output[index].complete && session->output[index].sequence == session->output_expected) next = &session->output[index];
                if (!next || session->stopping) break;
                error = live_consume_output(session, next); if (error) break;
            }
        } else {
            /* Reaping interleaves endpoints and may return later input URBs first. */
            for (;;) {
                struct live_slot *next = NULL;
                for (int index = 0; index < LIVE_QUEUE; index++)
                    if (session->input[index].complete && session->input[index].sequence == session->input_expected) next = &session->input[index];
                if (!next || session->stopping) break;
                error = live_consume_input(session, next); if (error) break;
            }
        }
        if (error) live_fail_locked(session, error);
        pthread_mutex_unlock(&session->mutex);
        if (error) break;
    }
    live_cancel(session); return NULL;
}
static void live_free(struct live_session *session) {
    if (!session) return;
    if(session->fd>=0)close(session->fd);
    for (int group = 0; group < 2; group++) for (int index = 0; index < LIVE_QUEUE; index++) {
        struct live_slot *slot = group ? &session->output[index] : &session->input[index];
        /* A timed-out cancellation must never free a kernel-owned DMA buffer.
           Java closes the USB connection immediately after native destruction. */
        if (!slot->pending) { free(slot->urb); free(slot->data); }
    }
    free(session->carrier.data); free(session->pcm.data);
    pthread_cond_destroy(&session->changed); pthread_mutex_destroy(&session->mutex); free(session);
}
JNIEXPORT jlong JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeOpen(
        JNIEnv *env, jclass cls, jint fd) {
    (void)cls;
    if (fd < 0) { live_throw(env, "USB descriptor is closed"); return 0; }
    struct live_session *session = calloc(1, sizeof(*session));
    if (!session) { live_throw(env, "No memory for USB pump"); return 0; }
    /* Keep the USBFS open-file description alive independently of the borrowed
       Java descriptor; this still shares its existing interface claims. */
    session->fd=fcntl(fd,F_DUPFD_CLOEXEC,0);session->muted=1;
    if(session->fd<0){free(session);live_throw(env,"Could not retain the authorized USB descriptor");return 0;}
    pthread_mutex_init(&session->mutex, NULL);
    pthread_condattr_t attr; pthread_condattr_init(&attr); pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_cond_init(&session->changed, &attr); pthread_condattr_destroy(&attr);
    session->carrier.capacity = LIVE_INPUT_CAPACITY; session->pcm.capacity = LIVE_OUTPUT_CAPACITY;
    session->carrier.data = malloc(LIVE_INPUT_CAPACITY); session->pcm.data = malloc(LIVE_OUTPUT_CAPACITY);
    int error = (!session->carrier.data || !session->pcm.data) ? ENOMEM : 0;
    for (int index = 0; index < LIVE_QUEUE && !error; index++) {
        error = live_allocate_slot(&session->input[index], 0x85, LIVE_INPUT_PACKET);
        if (!error) error = live_allocate_slot(&session->output[index], 0x06, LIVE_OUTPUT_PACKET);
    }
    if (!error) error = pthread_create(&session->worker, NULL, live_worker, session);
    if (!error) {
        session->worker_created = 1; pthread_mutex_lock(&session->mutex);
        while (!session->ready) pthread_cond_wait(&session->changed, &session->mutex);
        error = session->error; pthread_mutex_unlock(&session->mutex);
    }
    if (error) {
        if (session->worker_created) pthread_join(session->worker, NULL);
        live_free(session); live_throw(env, "Could not start continuous USB duplex"); return 0;
    }
    return (jlong)(intptr_t)session;
}
JNIEXPORT jboolean JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeClaimInterface(
        JNIEnv *env,jclass cls,jint fd,jint interface_id){
    (void)cls;
    if(fd<0||interface_id<0||interface_id>2){live_throw(env,"Invalid audio interface claim");return JNI_FALSE;}
    unsigned int number=(unsigned int)interface_id;
    if(ioctl(fd,USBDEVFS_CLAIMINTERFACE,&number)==0)return JNI_TRUE;
    if(errno!=EBUSY){live_throw(env,"Could not claim the USB audio interface");return JNI_FALSE;}
    struct usbdevfs_disconnect_claim claim;memset(&claim,0,sizeof(claim));
    claim.interface=number;claim.flags=USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER;
    strcpy(claim.driver,"snd-usb-audio");
    if(ioctl(fd,USBDEVFS_DISCONNECT_CLAIM,&claim)==0)return JNI_TRUE;
    char detail[160];snprintf(detail,sizeof(detail),"Atomic USB interface %u claim failed; errno=%d (%s)",number,errno,strerror(errno));
    live_throw(env,detail);return JNI_FALSE;
}
JNIEXPORT jint JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeRead(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray destination, jint offset, jint length, jint timeout) {
    (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session || !destination || offset < 0 || length <= 0 || timeout < 0 || timeout > 1500
            || offset > (*env)->GetArrayLength(env, destination) - length) { live_throw(env, "Invalid USB read bounds"); return -1; }
    struct timespec deadline = live_deadline(timeout);
    pthread_mutex_lock(&session->mutex);
    while (!session->carrier.size && !session->stopping && timeout > 0) {
        int result = pthread_cond_timedwait(&session->changed, &session->mutex, &deadline);
        if (result == ETIMEDOUT) break;
        if (result) { live_fail_locked(session, result); break; }
    }
    /* A broken ISO stream invalidates buffered encoded data as well. */
    if (session->error) {pthread_mutex_unlock(&session->mutex);live_transport_error(env,session,"USB capture continuity lost");return -1;}
    if (session->stopping) { pthread_mutex_unlock(&session->mutex); return -1; }
    size_t bytes = session->carrier.size; if (bytes > (size_t)length) bytes = (size_t)length;
    uint8_t scratch[4096]; size_t consumed = 0;
    while (consumed < bytes) {
        size_t chunk = bytes - consumed; if (chunk > sizeof(scratch)) chunk = sizeof(scratch);
        live_ring_get(&session->carrier, scratch, chunk);
        /* ART may pause the Java caller while copying. Never hold the USB pump
           mutex across a JNI array access; the ISO queue is only 16 ms deep. */
        pthread_mutex_unlock(&session->mutex);
        (*env)->SetByteArrayRegion(env, destination, offset + (jint)consumed, (jsize)chunk, (jbyte *)scratch);
        if ((*env)->ExceptionCheck(env)) return (jint)consumed;
        consumed += chunk;
        pthread_mutex_lock(&session->mutex);
        if(session->stopping||session->error)break;
    }
    pthread_mutex_unlock(&session->mutex); return (jint)consumed;
}
JNIEXPORT jint JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeWrite(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray source, jint offset, jint length, jint timeout) {
    (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session || !source || offset < 0 || length <= 0 || length % PCM_FRAME_BYTES || timeout < 0 || timeout > 1500
            || offset > (*env)->GetArrayLength(env, source) - length) { live_throw(env, "USB output must contain complete six-channel PCM16 frames"); return -1; }
    struct timespec deadline = live_deadline(timeout);
    pthread_mutex_lock(&session->mutex);
    while (session->pcm.capacity - session->pcm.size < PCM_FRAME_BYTES && !session->stopping && timeout > 0) {
        int result = pthread_cond_timedwait(&session->changed, &session->mutex, &deadline);
        if (result == ETIMEDOUT) break;
        if (result) { live_fail_locked(session, result); break; }
    }
    if (session->error) {pthread_mutex_unlock(&session->mutex);live_transport_error(env,session,"USB output stopped");return -1;}
    if (session->stopping) { pthread_mutex_unlock(&session->mutex); return -1; }
    size_t bytes = session->pcm.capacity - session->pcm.size;
    if (bytes > (size_t)length) bytes = (size_t)length;
    bytes -= bytes % PCM_FRAME_BYTES;
    uint8_t scratch[4092]; size_t copied = 0;
    while (copied < bytes) {
        size_t chunk = bytes - copied; if (chunk > sizeof(scratch)) chunk = sizeof(scratch);
        pthread_mutex_unlock(&session->mutex);
        (*env)->GetByteArrayRegion(env, source, offset + (jint)copied, (jsize)chunk, (jbyte *)scratch);
        if ((*env)->ExceptionCheck(env)) return (jint)copied;
        pthread_mutex_lock(&session->mutex);
        if(session->stopping||session->error)break;
        live_ring_put(&session->pcm, scratch, chunk); copied += chunk;
    }
    pthread_mutex_unlock(&session->mutex); return (jint)copied;
}
static void live_stats(struct live_session *session,jlong *values) {
    pthread_mutex_lock(&session->mutex);
    values[0] = (jlong)session->input_bytes; values[1] = (jlong)session->input_packets;
    values[2] = (jlong)session->output_bytes; values[3] = (jlong)session->output_packets;
    values[4] = (jlong)session->silence_frames; values[5] = (jlong)session->underrun_events;
    values[6] = (jlong)session->carrier.size; values[7] = (jlong)session->pcm.size;
    values[8] = session->error; values[9] = session->stopping;
    values[10] = (jlong)session->input_errors; values[11] = (jlong)session->output_errors;
    values[12] = (jlong)session->input_sequence; values[13] = (jlong)session->input_expected;
    values[14] = (jlong)session->gated_frames; values[15] = (jlong)session->priming_frames; values[16] = session->priming;
    values[17] = (jlong)session->refill_count; values[18] = session->muted;
    values[19] = (jlong)session->input_gaps; values[20] = (jlong)session->output_gaps;
    values[21] = session->input_last_frame; values[22] = session->output_last_frame;
    values[23] = session->input_timing_known; values[24] = session->output_timing_known;
    values[25] = (jlong)session->output_requested_bytes; values[26] = session->output_byte_accounting_known;
    pthread_mutex_unlock(&session->mutex);
}
JNIEXPORT jlongArray JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeStats(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session) return NULL;
    jlong values[27];live_stats(session,values);
    jlongArray result = (*env)->NewLongArray(env, 27);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 27, values); return result;
}
JNIEXPORT void JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeStatsInto(
        JNIEnv *env,jclass cls,jlong handle,jlongArray destination) {
    (void)cls;struct live_session *session=(struct live_session *)(intptr_t)handle;
    if(!session||!destination||(*env)->GetArrayLength(env,destination)!=27){live_throw(env,"Invalid USB telemetry buffer");return;}
    jlong values[27];live_stats(session,values);(*env)->SetLongArrayRegion(env,destination,0,27,values);
}
JNIEXPORT void JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeStop(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session) return;
    pthread_mutex_lock(&session->mutex); session->stopping = 1;
    /* Pending decoded output is discarded; the pump cancels already submitted URBs. */
    session->pcm.size = 0; session->carrier.size = 0;
    pthread_cond_broadcast(&session->changed); pthread_mutex_unlock(&session->mutex);
}
JNIEXPORT void JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeSetMuted(
        JNIEnv *env, jclass cls, jlong handle, jboolean muted) {
    (void)env; (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session) return;
    pthread_mutex_lock(&session->mutex);
    int next = muted ? 1 : 0;
    if (session->muted != next) {
        session->muted = next; session->pcm.size = 0; session->priming = !next;
        session->last_was_silent = 0;
    }
    pthread_cond_broadcast(&session->changed); pthread_mutex_unlock(&session->mutex);
}
JNIEXPORT void JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeClearOutput(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session) return;
    pthread_mutex_lock(&session->mutex); session->pcm.size = 0; session->priming = !session->muted;
    pthread_cond_broadcast(&session->changed); pthread_mutex_unlock(&session->mutex);
}
JNIEXPORT jint JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeDestroy(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls; struct live_session *session = (struct live_session *)(intptr_t)handle;
    if (!session) return 0;
    if (session->worker_created) pthread_join(session->worker, NULL);
    int error = session->error; live_free(session); return error;
}
/* Runs the real FIFO/gate/prime routines without a USB descriptor or any ioctl. */
JNIEXPORT jint JNICALL Java_br_com_sistema51_a34_usb_Cm6206DuplexTransport_nativeSoftwareSelfTest(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    int checks = 0;
    uint8_t storage[32], pattern[64], readback[32];
    for (int index = 0; index < 64; index++) pattern[index] = (uint8_t)index;
    struct live_ring ring = {storage, sizeof(storage), 0, 0};
    live_ring_put(&ring, pattern, 24); live_ring_get(&ring, readback, 20);
    int order = memcmp(readback, pattern, 20) == 0;
    live_ring_put(&ring, pattern + 24, 20); live_ring_get(&ring, readback, 24);
    if (order && ring.size == 0 && memcmp(readback, pattern + 20, 24) == 0) checks |= 1;
    struct live_slot input_slot={0},output_slot={0};
    int allocated_input=live_allocate_slot(&input_slot,0x85,LIVE_INPUT_PACKET),allocated_output=live_allocate_slot(&output_slot,0x06,LIVE_OUTPUT_PACKET);
    if(allocated_input||allocated_output||input_slot.urb->number_of_packets!=16||output_slot.urb->number_of_packets!=4
        ||input_slot.urb->buffer_length!=3200||output_slot.urb->buffer_length!=2304)checks&=~1;
    free(input_slot.urb);free(input_slot.data);free(output_slot.urb);free(output_slot.data);
    struct live_session session; memset(&session, 0, sizeof(session));
    session.pcm.capacity = LIVE_OUTPUT_CAPACITY; session.pcm.data = calloc(1, LIVE_OUTPUT_CAPACITY);
    struct live_slot slot; memset(&slot, 0, sizeof(slot)); slot.data = calloc(LIVE_PACKETS, LIVE_OUTPUT_PACKET);
    uint8_t *pcm = malloc(LIVE_PRIME_BYTES);
    if (!session.pcm.data || !slot.data || !pcm) { free(session.pcm.data); free(slot.data); free(pcm); return checks; }
    pthread_cond_init(&session.changed, NULL);
    for (int frame = 0; frame < 3072; frame++) for (int channel = 0; channel < 6; channel++) {
        unsigned int sample = (unsigned int)(frame + channel * 4000 + 1);
        pcm[frame * 12 + channel * 2] = (uint8_t)sample;
        pcm[frame * 12 + channel * 2 + 1] = (uint8_t)(sample >> 8);
    }
    session.muted = 1; live_ring_put(&session.pcm, pcm, LIVE_PACKETS * LIVE_OUTPUT_PACKET);
    live_fill_output(&session, &slot);
    int zero = 1; for (int index = 0; index < LIVE_PACKETS * LIVE_OUTPUT_PACKET; index++) if (slot.data[index]) zero = 0;
    if (zero && !session.pcm.size && session.gated_frames == LIVE_PACKETS * 48 && !session.underrun_events) checks |= 2;
    session.muted = 0; session.priming = 1;
    live_ring_put(&session.pcm, pcm, LIVE_PRIME_BYTES / 2); live_fill_output(&session, &slot);
    zero = 1; for (int index = 0; index < LIVE_PACKETS * LIVE_OUTPUT_PACKET; index++) if (slot.data[index]) zero = 0;
    if (zero && session.priming && session.pcm.size == LIVE_PRIME_BYTES / 2 && !session.underrun_events) checks |= 4;
    live_ring_put(&session.pcm, pcm + LIVE_PRIME_BYTES / 2, LIVE_PRIME_BYTES / 2);
    live_fill_output(&session, &slot);
    if (!session.priming && session.pcm.size == LIVE_PRIME_BYTES - LIVE_PACKETS * LIVE_OUTPUT_PACKET
            && memcmp(slot.data, pcm, LIVE_PACKETS * LIVE_OUTPUT_PACKET) == 0) checks |= 8;
    session.pcm.head = 0; session.pcm.size = 0;
    live_ring_put(&session.pcm, pcm, 47 * PCM_FRAME_BYTES); live_fill_output(&session, &slot);
    zero = 1; for (int index = 0; index < LIVE_PACKETS * LIVE_OUTPUT_PACKET; index++) if (slot.data[index]) zero = 0;
    if (zero && session.priming && session.pcm.size == 47 * PCM_FRAME_BYTES
            && session.underrun_events == 1 && session.refill_count == 1) checks |= 16;
    pthread_cond_destroy(&session.changed); free(session.pcm.data); free(slot.data); free(pcm); return checks;
}
