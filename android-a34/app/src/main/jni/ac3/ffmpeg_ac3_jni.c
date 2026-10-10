#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <math.h>
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>

typedef struct {
    AVCodecContext *codec;
    AVFrame *frame;
    AVPacket *packet;
    int last_layout;
} Decoder;

static void fail(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

static void destroy(Decoder *decoder) {
    if (!decoder) return;
    av_packet_free(&decoder->packet);
    av_frame_free(&decoder->frame);
    avcodec_free_context(&decoder->codec);
    free(decoder);
}

JNIEXPORT jlong JNICALL Java_br_com_sistema51_a34_io_SoftwareAc3Decoder_nativeCreate(JNIEnv *env, jclass cls) {
    (void)cls;
    const AVCodec *codec = avcodec_find_decoder_by_name("ac3");
    Decoder *decoder = calloc(1, sizeof(*decoder));
    if (!codec || !decoder) { destroy(decoder); fail(env, "Decoder AC-3 indisponível."); return 0; }
    decoder->codec = avcodec_alloc_context3(codec);
    decoder->frame = av_frame_alloc(); decoder->packet = av_packet_alloc();
    if (!decoder->codec || !decoder->frame || !decoder->packet) {
        destroy(decoder); fail(env, "Memória insuficiente para o decoder AC-3."); return 0;
    }
    decoder->codec->err_recognition = AV_EF_CRCCHECK | AV_EF_CAREFUL | AV_EF_EXPLODE;
    decoder->codec->thread_count = 1;
    if (av_opt_set_double(decoder->codec->priv_data, "drc_scale", 0.0, 0) < 0
            || av_opt_set_int(decoder->codec->priv_data, "target_level", 0, 0) < 0
            || av_opt_set_int(decoder->codec->priv_data, "heavy_compr", 0, 0) < 0
            || avcodec_open2(decoder->codec, codec, NULL) < 0) {
        destroy(decoder); fail(env, "Falha ao configurar o decoder AC-3 sem DRC/dialnorm."); return 0;
    }
    return (jlong)(intptr_t)decoder;
}

static jfloatArray decode(JNIEnv *env,jlong handle,jbyteArray input,jintArray metadata,jfloatArray destination) {
    Decoder *decoder = (Decoder *)(intptr_t)handle;
    if (!decoder || !input || !metadata || (*env)->GetArrayLength(env, metadata) != 3) {
        fail(env, "Argumento inválido para o decoder AC-3."); return NULL;
    }
    jsize length = (*env)->GetArrayLength(env, input);
    if (length < 128 || length > 2560) { fail(env, "Quadro AC-3 fora do limite."); return NULL; }
    av_packet_unref(decoder->packet);
    if (av_new_packet(decoder->packet, length) < 0) { fail(env, "Memória insuficiente para o quadro AC-3."); return NULL; }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)decoder->packet->data);
    if ((*env)->ExceptionCheck(env)) return NULL;
    uint8_t *data = decoder->packet->data;
    /* Restrict to AC-3 48 kHz with one whole frame, before invoking the decoder. */
    static const int rates[] = {32,40,48,56,64,80,96,112,128,160,192,224,256,320,384,448,512,576,640};
    int size_code = data[4] & 63;
    if (data[0] != 0x0b || data[1] != 0x77 || data[4] >> 6 != 0 || data[5] >> 3 > 8
            || size_code > 37 || rates[size_code / 2] * 4 != length) {
        fail(env, "Use exatamente um quadro AC-3 íntegro a 48 kHz."); return NULL;
    }
    int acmod = data[6] >> 5, lfe_bit = 51;
    if ((acmod & 1) && acmod != 1) lfe_bit += 2;
    if (acmod & 4) lfe_bit += 2;
    if (acmod == 2) lfe_bit += 2;
    int lfe = (data[lfe_bit / 8] >> (7 - lfe_bit % 8)) & 1;
    int layout = 1 + acmod * 2 + lfe;
    if (decoder->last_layout && decoder->last_layout != layout) avcodec_flush_buffers(decoder->codec);
    decoder->last_layout = layout;
    av_frame_unref(decoder->frame);
    if (avcodec_send_packet(decoder->codec, decoder->packet) < 0
            || avcodec_receive_frame(decoder->codec, decoder->frame) < 0) {
        avcodec_flush_buffers(decoder->codec);
        fail(env, "Quadro AC-3 inválido, incompleto ou com CRC incorreto."); return NULL;
    }
    AVFrame *frame = decoder->frame;
    int channels = frame->ch_layout.nb_channels;
    if (frame->format != AV_SAMPLE_FMT_FLTP || frame->nb_samples != 1536 || frame->sample_rate != 48000
            || !(channels == 1 || channels == 2 || channels == 6)
            || (frame->flags & AV_FRAME_FLAG_CORRUPT) || frame->decode_error_flags) {
        avcodec_flush_buffers(decoder->codec);
        fail(env, "Formato/layout AC-3 não suportado ou quadro corrompido."); return NULL;
    }
    int map[6];
    if (channels == 1) map[0] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_FRONT_CENTER);
    else {
        map[0] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_FRONT_LEFT);
        map[1] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_FRONT_RIGHT);
        if (channels == 6) {
            map[2] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_FRONT_CENTER);
            map[3] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_LOW_FREQUENCY);
            map[4] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_SIDE_LEFT);
            map[5] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_SIDE_RIGHT);
            if (map[4] < 0) map[4] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_BACK_LEFT);
            if (map[5] < 0) map[5] = av_channel_layout_index_from_channel(&frame->ch_layout, AV_CHAN_BACK_RIGHT);
        }
    }
    for (int ch = 0; ch < channels; ch++) if (map[ch] < 0 || map[ch] >= channels) {
        avcodec_flush_buffers(decoder->codec); fail(env, "Mapa AC-3 não corresponde a mono, estéreo ou 5.1."); return NULL;
    }
    float samples[1536 * 6];
    for (int ch = 0; ch < channels; ch++) {
        const float *plane = (const float *)frame->extended_data[map[ch]];
        for (int i = 0; i < 1536; i++) {
            float value = plane[i];
            if (!isfinite(value)) { avcodec_flush_buffers(decoder->codec); fail(env, "PCM não finito no decoder AC-3."); return NULL; }
            samples[i * channels + ch] = value;
        }
    }
    if(destination&&(*env)->GetArrayLength(env,destination)<1536*channels){fail(env,"Buffer PCM AC-3 pequeno.");return NULL;}
    jfloatArray result = destination?destination:(*env)->NewFloatArray(env, 1536 * channels);
    if (!result) return NULL;
    (*env)->SetFloatArrayRegion(env, result, 0, 1536 * channels, samples);
    jint meta[] = {channels, 48000, 1536};
    (*env)->SetIntArrayRegion(env, metadata, 0, 3, meta);
    return result;
}

JNIEXPORT jfloatArray JNICALL Java_br_com_sistema51_a34_io_SoftwareAc3Decoder_nativeDecode(
        JNIEnv *env,jclass cls,jlong handle,jbyteArray input,jintArray metadata){
    (void)cls;return decode(env,handle,input,metadata,NULL);
}
JNIEXPORT void JNICALL Java_br_com_sistema51_a34_io_SoftwareAc3Decoder_nativeDecodeInto(
        JNIEnv *env,jclass cls,jlong handle,jbyteArray input,jintArray metadata,jfloatArray destination){
    (void)cls;
    if(!destination){fail(env,"Buffer PCM AC-3 ausente.");return;}
    decode(env,handle,input,metadata,destination);
}

JNIEXPORT void JNICALL Java_br_com_sistema51_a34_io_SoftwareAc3Decoder_nativeReset(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    Decoder *decoder = (Decoder *)(intptr_t)handle;
    if (decoder) avcodec_flush_buffers(decoder->codec);
}

JNIEXPORT void JNICALL Java_br_com_sistema51_a34_io_SoftwareAc3Decoder_nativeDestroy(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    destroy((Decoder *)(intptr_t)handle);
}
