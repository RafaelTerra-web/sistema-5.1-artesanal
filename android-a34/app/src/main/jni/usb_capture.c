#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <poll.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>

/* Diagnostic only: private ISO capture and optional zero-filled output. */
#define QUEUE 8
#define SLOTS (QUEUE*2)
#define PACKETS 16
struct capture_slot { struct usbdevfs_urb *urb; uint8_t *data; int pending; };
static int64_t monotonic_ms(void) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec*1000+now.tv_nsec/1000000;
}
static void fail(JNIEnv *env, const char *message) {
    jclass cls=(*env)->FindClass(env,"java/io/IOException");
    if(cls)(*env)->ThrowNew(env,cls,message);
}
JNIEXPORT jlongArray JNICALL Java_br_com_sistema51_a34_usb_RawUsbCapture_capture(
    JNIEnv *env, jclass cls, jint fd, jint endpoint, jint packet_bytes, jint seconds, jint file_fd, jint output_endpoint, jint output_packet_bytes) {
    (void)cls;
    if(endpoint<128||endpoint>255||packet_bytes<4||packet_bytes>1023||seconds<1||seconds>10){fail(env,"Invalid capture bounds");return NULL;}
    if(output_endpoint&&(output_endpoint<1||output_endpoint>127||output_packet_bytes<4||output_packet_bytes>1023)){fail(env,"Invalid silence output bounds");return NULL;}
    int slot_count=output_endpoint?SLOTS:QUEUE;
    struct capture_slot slots[SLOTS]; memset(slots,0,sizeof(slots));
    jlong stats[9]={0,0,0,0,0,0,0,0,0}; /* input metrics, errno, output packets/errors/requested bytes */
    int failed=0;
    for(int i=0;i<slot_count;i++){
        int width=i<QUEUE?packet_bytes:output_packet_bytes;
        slots[i].urb=calloc(1,sizeof(struct usbdevfs_urb)+PACKETS*sizeof(struct usbdevfs_iso_packet_desc));
        slots[i].data=calloc(PACKETS,(size_t)width);
        if(!slots[i].urb||!slots[i].data){failed=ENOMEM;goto cleanup;}
        struct usbdevfs_urb *urb=slots[i].urb;
        urb->type=USBDEVFS_URB_TYPE_ISO;urb->endpoint=(unsigned char)(i<QUEUE?endpoint:output_endpoint);
        urb->flags=USBDEVFS_URB_ISO_ASAP;urb->buffer=slots[i].data;
        urb->buffer_length=PACKETS*width;urb->number_of_packets=PACKETS;
        urb->usercontext=&slots[i];
        for(int p=0;p<PACKETS;p++)urb->iso_frame_desc[p].length=(unsigned int)width;
        if(ioctl(fd,USBDEVFS_SUBMITURB,urb)<0){failed=errno;goto cleanup;}
        slots[i].pending=1;
    }
    int64_t deadline=monotonic_ms()+seconds*1000;
    while(monotonic_ms()<deadline){
        struct usbdevfs_urb *done=NULL;
        if(ioctl(fd,USBDEVFS_REAPURBNDELAY,&done)<0){
            if(errno==EAGAIN){struct pollfd wait={fd,POLLOUT,0};poll(&wait,1,20);continue;}
            if(errno==EINTR)continue;
            failed=errno;break;
        }
        if(!done||!done->usercontext){failed=EIO;break;}
        struct capture_slot *slot=done->usercontext;
        if(slot<slots||slot>=slots+slot_count||slot->urb!=done){failed=EIO;break;}
        slot->pending=0;
        stats[4]++;
        if(done->status<0){failed=-done->status;break;}
        for(int p=0;p<PACKETS;p++){
            struct usbdevfs_iso_packet_desc *packet=&done->iso_frame_desc[p];
            if(slot>=slots+QUEUE){stats[6]++;if(packet->status)stats[7]++;stats[8]+=packet->length;continue;}
            stats[1]++;
            if(packet->status||packet->actual_length>(unsigned int)packet_bytes){stats[2]++;continue;}
            if(packet->actual_length!=(unsigned int)packet_bytes)stats[3]++;
            unsigned int offset=0;
            while(offset<packet->actual_length){
                ssize_t written=write(file_fd,slot->data+p*packet_bytes+offset,packet->actual_length-offset);
                if(written<0&&errno==EINTR)continue;
                if(written<=0){failed=written<0?errno:EIO;goto cleanup;}
                offset+=(unsigned int)written;stats[0]+=written;
            }
        }
        for(int p=0;p<PACKETS;p++){done->iso_frame_desc[p].actual_length=0;done->iso_frame_desc[p].status=0;}
        done->status=0;done->actual_length=0;done->error_count=0;
        if(ioctl(fd,USBDEVFS_SUBMITURB,done)<0){failed=errno;break;}
        slot->pending=1;
    }
cleanup:;
    /* Cancel and reap every submitted URB before freeing userspace memory. */
    int pending=0;
    for(int i=0;i<slot_count;i++)if(slots[i].pending){ioctl(fd,USBDEVFS_DISCARDURB,slots[i].urb);pending++;}
    int64_t stop=monotonic_ms()+1500;
    while(pending&&monotonic_ms()<stop){struct usbdevfs_urb *done=NULL;
        if(ioctl(fd,USBDEVFS_REAPURBNDELAY,&done)<0){if(errno==EAGAIN){usleep(1000);continue;}break;}
        for(int i=0;i<slot_count;i++)if(slots[i].urb==done&&slots[i].pending){slots[i].pending=0;pending--;}
    }
    /* On failed cancellation, leak these bounded diagnostic buffers rather than
       freeing memory the kernel may still reference; closing USB cancels DMA. */
    if(pending)failed=ETIMEDOUT;
    for(int i=0;i<slot_count;i++)if(!slots[i].pending){free(slots[i].urb);free(slots[i].data);}
    stats[5]=failed;
    jlongArray result=(*env)->NewLongArray(env,9);
    if(result)(*env)->SetLongArrayRegion(env,result,0,9,stats);
    return result;
}
JNIEXPORT jint JNICALL Java_br_com_sistema51_a34_usb_RawUsbCapture_reconnect(
    JNIEnv *env,jclass cls,jint fd,jint interface_id){
    (void)env;(void)cls;
    struct usbdevfs_ioctl request={interface_id,USBDEVFS_CONNECT,NULL};
    return ioctl(fd,USBDEVFS_IOCTL,&request)<0?-errno:0;
}
