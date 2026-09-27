package com.example.elink.punch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.Optional;

/**
 * 一个最小的 STUN 客户端：只做一件事——问服务器"我在你这边看起来是什么地址和端口"。
 *
 * <p>为什么打洞需要它：对称 NAT 会给**每个不同的目标**分配不同的公网端口，所以本机端口、以及"对 STUN
 * 服务器的映射端口"都不能直接当成"对好友的映射端口"。但我们可以用同一个本地端口向多个 STUN 服务器查询，
 * 观察 NAT 分配出来的端口序列（例如 40000、40001、40002……），从而推断它的分配规律（步长与范围），供打洞
 * 时**预测**对方可能占用的端口。
 *
 * <p>只实现了 RFC 5389 的 Binding 请求与 XOR-MAPPED-ADDRESS 解析，不引入任何外部依赖。
 *
 * <p>线程：方法会阻塞（轮询 + 短暂睡眠）直到收到响应或超时，因此不要在游戏主线程调用。
 */
public final class StunClient {
    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int TYPE_BINDING_REQUEST = 0x0001;
    private static final int TYPE_BINDING_RESPONSE = 0x0101;
    private static final int ATTR_MAPPED_ADDRESS = 0x0001;
    private static final int ATTR_XOR_MAPPED_ADDRESS = 0x0020;
    private static final int HEADER_SIZE = 20;
    private static final int TRANSACTION_ID_SIZE = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 服务器观察到的"我们的公网地址"。 */
    public record Mapping(InetAddress address, int port) {}

    private StunClient() {}

    /**
     * 用给定的 UDP 通道发一次 Binding 请求，并等待响应。
     *
     * <p>通道应当处于**非阻塞**模式（{@code HolePuncher} 里的通道就是），这样超时控制才有效。
     *
     * @param channel       已经绑定的 UDP 通道；观察到的映射对应它的本地端口
     * @param server        STUN 服务器地址
     * @param timeoutMillis 等待响应的最长时间
     * @return 映射地址；超时或响应无法解析时返回 empty
     */
    public static Optional<Mapping> query(
            final DatagramChannel channel, final InetSocketAddress server, final int timeoutMillis) {
        final byte[] transactionId = new byte[TRANSACTION_ID_SIZE];
        RANDOM.nextBytes(transactionId);

        try {
            channel.send(ByteBuffer.wrap(buildRequest(transactionId)), server);
        } catch (IOException e) {
            return Optional.empty();
        }

        final long deadline = System.currentTimeMillis() + timeoutMillis;
        final ByteBuffer buffer = ByteBuffer.allocate(1024);

        while (System.currentTimeMillis() < deadline) {
            buffer.clear();

            final InetSocketAddress from;
            try {
                from = (InetSocketAddress) channel.receive(buffer);
            } catch (IOException e) {
                return Optional.empty();
            }

            if (from == null) {
                // 非阻塞通道上暂时没有数据，稍后再看
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                continue;
            }

            // 只接受来自目标 STUN 服务器的包
            if (!from.getAddress().equals(server.getAddress())) {
                continue;
            }

            buffer.flip();
            final byte[] response = new byte[buffer.remaining()];
            buffer.get(response);

            final Optional<Mapping> mapping = parseResponse(response, transactionId);
            if (mapping.isPresent()) {
                return mapping;
            }
        }
        return Optional.empty();
    }

    private static byte[] buildRequest(final byte[] transactionId) {
        final ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.putShort((short) TYPE_BINDING_REQUEST);
        buffer.putShort((short) 0); // 不带任何属性
        buffer.putInt(MAGIC_COOKIE);
        buffer.put(transactionId);
        return buffer.array();
    }

    /** 解析 Binding 响应，取出 XOR-MAPPED-ADDRESS（或退化情况下的 MAPPED-ADDRESS）。 */
    private static Optional<Mapping> parseResponse(final byte[] data, final byte[] transactionId) {
        if (data.length < HEADER_SIZE) {
            return Optional.empty();
        }
        if (readShort(data, 0) != TYPE_BINDING_RESPONSE || readInt(data, 4) != MAGIC_COOKIE) {
            return Optional.empty();
        }
        // 事务 ID 必须一致，避免把别人请求的响应当成自己的
        for (int i = 0; i < TRANSACTION_ID_SIZE; i++) {
            if (data[8 + i] != transactionId[i]) {
                return Optional.empty();
            }
        }

        final int length = readShort(data, 2);
        int offset = HEADER_SIZE;
        final int end = Math.min(data.length, HEADER_SIZE + length);

        // 属性是 TLV 结构，逐个跳过
        while (offset + 4 <= end) {
            final int attributeType = readShort(data, offset);
            final int attributeLength = readShort(data, offset + 2);
            final int valueOffset = offset + 4;

            if (attributeType == ATTR_XOR_MAPPED_ADDRESS || attributeType == ATTR_MAPPED_ADDRESS) {
                final Optional<Mapping> mapping =
                        parseAddress(
                                data,
                                valueOffset,
                                attributeLength,
                                attributeType == ATTR_XOR_MAPPED_ADDRESS,
                                transactionId);
                if (mapping.isPresent()) {
                    return mapping;
                }
            }

            // 属性值按 4 字节对齐
            offset = valueOffset + attributeLength + ((4 - attributeLength % 4) % 4);
        }
        return Optional.empty();
    }

    private static Optional<Mapping> parseAddress(
            final byte[] data,
            final int offset,
            final int length,
            final boolean xor,
            final byte[] transactionId) {
        if (length < 4 || offset + length > data.length) {
            return Optional.empty();
        }

        final int family = data[offset + 1] & 0xFF;
        int port = readShort(data, offset + 2);
        if (xor) {
            port ^= (MAGIC_COOKIE >>> 16);
        }

        try {
            if (family == 0x01) { // IPv4
                if (length < 8) {
                    return Optional.empty();
                }
                final byte[] address = new byte[4];
                System.arraycopy(data, offset + 4, address, 0, 4);
                if (xor) {
                    for (int i = 0; i < 4; i++) {
                        address[i] ^= (byte) (MAGIC_COOKIE >>> (24 - 8 * i));
                    }
                }
                return Optional.of(new Mapping(InetAddress.getByAddress(address), port));
            }

            if (family == 0x02) { // IPv6：XOR 端口与地址，地址还要异或事务 ID
                if (length < 20) {
                    return Optional.empty();
                }
                final byte[] address = new byte[16];
                System.arraycopy(data, offset + 4, address, 0, 16);
                if (xor) {
                    for (int i = 0; i < 4; i++) {
                        address[i] ^= (byte) (MAGIC_COOKIE >>> (24 - 8 * i));
                    }
                    for (int i = 0; i < TRANSACTION_ID_SIZE; i++) {
                        address[4 + i] ^= transactionId[i];
                    }
                }
                return Optional.of(new Mapping(InetAddress.getByAddress(address), port));
            }
        } catch (Exception ignored) {
            // 地址字节不合法：当作没解析出来
        }
        return Optional.empty();
    }

    private static int readShort(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
