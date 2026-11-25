package ru.miacomsoft.webrdp;

import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class StunServer extends Thread {
    private static final String TAG = "StunServer";
    private final int port;
    private DatagramSocket socket;
    private volatile boolean running = false;

    public StunServer(int port) {
        this.port = port;
    }

    public void startServer() {
        running = true;
        start();
    }

    public void stopServer() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(port);
            byte[] buffer = new byte[1024];
            Log.i(TAG, "STUN Server started on UDP port " + port);

            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "STUN Server error: " + e.getMessage());
        }
    }

    private void handlePacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int length = packet.getLength();
        if (length < 20) return;

        // Проверяем, что это STUN Binding Request (тип 0x0001)
        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (type != 0x0001) return;

        // Формируем STUN Binding Success Response (тип 0x0101)
        byte[] response = new byte[32];
        response[0] = 0x01; response[1] = 0x01;
        response[2] = 0x00; response[3] = 0x0C; // Длина атрибута: 12 байт

        // Magic Cookie (всегда 0x2112A442)
        response[4] = 0x21; response[5] = 0x12; response[6] = (byte) 0xA4; response[7] = 0x42;

        // Копируем Transaction ID из запроса (12 байт)
        System.arraycopy(data, 8, response, 8, 12);

        // Атрибут XOR-MAPPED-ADDRESS (0x0020)
        response[20] = 0x00; response[21] = 0x20;
        response[22] = 0x00; response[23] = 0x08;
        // Reserved (0x00), Family (0x01 = IPv4)
        response[24] = 0x00; response[25] = 0x01;

        // XOR порта с Magic Cookie
        int xorPort = packet.getPort() ^ 0x2112;
        response[26] = (byte) (xorPort >> 8);
        response[27] = (byte) (xorPort & 0xFF);

        // XOR IP-адреса с Magic Cookie
        byte[] ip = packet.getAddress().getAddress();
        byte[] magic = {0x21, 0x12, (byte) 0xA4, 0x42};
        for (int i = 0; i < 4; i++) {
            response[28 + i] = (byte) (ip[i] ^ magic[i]);
        }

        try {
            DatagramPacket outPacket = new DatagramPacket(response, response.length, packet.getAddress(), packet.getPort());
            socket.send(outPacket);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send STUN response", e);
        }
    }
}