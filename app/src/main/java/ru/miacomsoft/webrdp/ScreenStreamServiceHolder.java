package ru.miacomsoft.webrdp;

/**
 * Простейший синглтон-холдер ссылки на живой экземпляр ScreenStreamService.
 * Нужен, чтобы SignalingServer мог вызывать методы сервиса (WebRTC) без bindService().
 */
public final class ScreenStreamServiceHolder {

    private static volatile ScreenStreamService instance;

    private ScreenStreamServiceHolder() {}

    static void set(ScreenStreamService s) {
        instance = s;
    }

    public static ScreenStreamService get() {
        return instance;
    }
}