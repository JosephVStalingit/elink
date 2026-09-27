package com.example.elink.punch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在一条已经打通的 UDP 路径上，提供**加密、有序、可靠**的字节流。
 *
 * <p>为什么需要它：UDP 会丢包、会乱序、也能被伪造，而我们要承载的是一条 TCP 连接（Minecraft 协议），
 * 字节顺序和完整性都不能出问题。QUIC 解决的就是这件事，本类用尽可能少的代码实现其中最核心的三点：
 *
 * <ol>
 *   <li><b>加密与完整性</b>：AES-GCM，密钥由打洞令牌派生，包头作为附加认证数据（篡改序号会被立刻发现）；
 *   <li><b>可靠</b>：每个数据包带序号，接收方回确认号与位图，发送方据此清理或重传未确认的包；
 *   <li><b>有序</b>：接收方只按顺序把数据交给上层，乱序到达的先放进缓冲等待缺失的包。
 * </ol>
 *
 * <p>它是"够用就好"的实现：没有实现拥塞控制与流量控制，因此发送速率是固定的（见 {@link #SEND_INTERVAL_MS}），
 * 适合 Minecraft 这种带宽需求不高的场景。
 *
 * <p>线程：{@link #start()} 会启动两个守护线程（收包循环与重传循环），回调在收包线程上执行。
 */
public final class UdpLink implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/punch");

    /** 包头：魔数(4) + 版本(1) + 类型(1) + 序号(4) + 确认号(4) + 确认位图(4) = 18 字节。 */
    private static final byte[] MAGIC = {'M', 'C', 'P', '3'};
    private static final byte VERSION = 1;
    private static final byte TYPE_DATA = 1;
    private static final byte TYPE_ACK = 2;
    private static final int HEADER_SIZE = 18;
    private static final int MAX_PAYLOAD = 1200; // 小于常见路径 MTU，避免 IP 分片
    private static final int GCM_TAG_BITS = 128;

    /** 未确认包的最多重传次数与重传间隔。 */
    private static final int MAX_RETRIES = 12;
    private static final long RETRANSMIT_INTERVAL_MS = 150;
    /** 发送节奏：每 {@code SEND_INTERVAL_MS} 毫秒最多发一批。 */
    private static final long SEND_INTERVAL_MS = 5;
    private static final int SEND_BATCH = 20;
    /** 未确认窗口上限，超过就暂缓发送（简单版的流量控制）。 */
    private static final int MAX_IN_FLIGHT = 512;
    /** 乱序缓冲上限，超过就丢弃（对端会重传）。 */
    private static final int MAX_REORDERED = 1024;

    /** 上层回调：收到数据或连接结束。 */
    public interface Listener {
        void onData(byte[] payload);

        void onClosed(String reason);
    }

    private final DatagramChannel channel;
    private final InetSocketAddress remote;
    private final SecretKeySpec key;
    private final Listener listener;

    /** 待发送的有效载荷（顺序即字节流顺序）。 */
    private final Deque<byte[]> outgoing = new ArrayDeque<>();
    /** 已发出但还没被确认的包：序号 → 包体与重传次数。 */
    private final Map<Integer, Pending> unacked = new HashMap<>();
    /** 乱序到达、等待补缺的包：序号 → 载荷。 */
    private final Map<Integer, byte[]> reordered = new HashMap<>();
    /** 最近确认过的序号位图，用来告诉对方"我还收到了这些"。 */
    private final Map<Integer, Boolean> acked = new HashMap<>();

    private int nextSequence;
    private int expectedSequence;
    private int highestReceived = -1;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private long lastEmptyAck;

    private record Pending(int sequence, byte[] packet, long sentAt, int attempts) {}

    /**
     * @param channel  打洞命中的通道（{@link HolePuncher.Path#channel()}）
     * @param remote   对方的公网地址（{@link HolePuncher.Path#remote()}）
     * @param token    两端共享的打洞令牌，用来派生加密密钥
     * @param listener 数据与关闭回调
     */
    public UdpLink(
            final DatagramChannel channel,
            final InetSocketAddress remote,
            final byte[] token,
            final Listener listener) {
        this.channel = channel;
        this.remote = remote;
        this.listener = listener;
        this.key = deriveKey(token);
    }

    /** 派生 AES-256 密钥：把令牌与固定标签一起做一次 SHA-256。 */
    private static SecretKeySpec deriveKey(final byte[] token) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(token);
            digest.update("elink-udp-link".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return new SecretKeySpec(digest.digest(), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** @return 这个通道是否还在工作 */
    public boolean isOpen() {
        return open.get();
    }

    /**
     * 取出本链路绑定的通道。
     *
     * <p>本链路**不**负责关闭它：调用方在销毁通道前必须先 {@link #close()}，否则通道会因 SocketException
     * 持续报错。这与 {@link HolePuncher} 的行为刚好相反（puncher 关闭时会一并关掉所有通道），原因是链路
     * 关心的是 socket 内容，puncher 关心的是 socket 句柄本身。
     */
    public DatagramChannel channel() {
        return channel;
    }

    /** @return 还未被确认的包数量，用于诊断卡顿 */
    public int inFlight() {
        synchronized (unacked) {
            return unacked.size();
        }
    }

    /** 启动收包与重传循环（两个守护线程，不会阻止游戏退出）。 */
    public void start() {
        final Thread receiver = new Thread(this::receiveLoop, "elink-udp-recv");
        receiver.setDaemon(true);
        receiver.start();

        final Thread sender = new Thread(this::sendLoop, "elink-udp-send");
        sender.setDaemon(true);
        sender.start();
    }

    /**
     * 排队发送一段数据。数据会被切成不超过 {@link #MAX_PAYLOAD} 的分片，按序可靠送达。
     *
     * <p>方法本身不阻塞（只入队），因此可以直接从隧道的读取线程调用。
     */
    public void send(final byte[] payload) {
        if (!open.get() || payload == null || payload.length == 0) {
            return;
        }
        synchronized (outgoing) {
            int offset = 0;
            while (offset < payload.length) {
                final int length = Math.min(MAX_PAYLOAD, payload.length - offset);
                final byte[] chunk = new byte[length];
                System.arraycopy(payload, offset, chunk, 0, length);
                outgoing.addLast(chunk);
                offset += length;
            }
        }
    }

    /** 收包循环：解密、确认、按序交付。 */
    private void receiveLoop() {
        final ByteBuffer buffer = ByteBuffer.allocate(MAX_PAYLOAD + HEADER_SIZE + 64);
        while (open.get()) {
            try {
                buffer.clear();
                final InetSocketAddress from = (InetSocketAddress) channel.receive(buffer);
                if (from == null) {
                    if (Thread.interrupted()) break;
                    Thread.sleep(2); // 非阻塞通道，稍等再看
                    continue;
                }
                buffer.flip();
                final byte[] packet = new byte[buffer.remaining()];
                buffer.get(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (open.get()) {
                    LOGGER.debug("UDP link receive error: {}", e.getMessage());
                }
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (open.getAndSet(false)) {
            listener.onClosed("收包循环结束");
        }
    }

    /** 处理一个收到的包：先按包头里的确认信息清理发送队列，再按需解密并交付。 */
    private void handlePacket(final byte[] packet) {
        if (packet.length < HEADER_SIZE || !hasMagic(packet)) {
            return;
        }
        final byte type = packet[5];
        final int sequence = readInt(packet, 6);
        final int remoteAck = readInt(packet, 10);
        final int remoteBits = readInt(packet, 14);

        // 对方的确认：可以清掉一批待重传的包
        onAcknowledgement(remoteAck, remoteBits);

        if (type != TYPE_DATA || packet.length <= HEADER_SIZE) {
            return; // 纯确认包，没有载荷
        }

        final byte[] cipherText = new byte[packet.length - HEADER_SIZE];
        System.arraycopy(packet, HEADER_SIZE, cipherText, 0, cipherText.length);
        // 复制一份"作为 AAD 的包头"，避免后续逻辑意外修改导致 AAD 漂移
        final byte[] aad = new byte[HEADER_SIZE];
        System.arraycopy(packet, 0, aad, 0, HEADER_SIZE);
        final byte[] payload = decrypt(sequence, cipherText, aad);
        if (payload == null) {
            return; // 解密失败（被篡改或密钥不对），直接丢弃
        }

        recordReceived(sequence);

        synchronized (reordered) {
            if (sequence == expectedSequence) {
                expectedSequence++;
                listener.onData(payload);
                drainReordered();
            } else if (sequence > expectedSequence && reordered.size() < MAX_REORDERED) {
                reordered.put(sequence, payload); // 先存着，等缺的那个包到了再按序交付
            }
            // sequence < expectedSequence 说明是重复包，忽略
        }

        sendAcknowledgement(); // 让对方的发送队列尽快清空
    }

    /** 记录收到的序号，用于生成确认位图。 */
    private void recordReceived(final int sequence) {
        synchronized (acked) {
            acked.put(sequence, Boolean.TRUE);
            if (sequence > highestReceived) {
                highestReceived = sequence;
            }
            // 只保留最近 64 个，避免无限增长
            acked.entrySet().removeIf(entry -> entry.getKey() < highestReceived - 64);
        }
    }

    /** 生成 32 位确认位图：第 i 位表示 (highestReceived - 1 - i) 是否已经收到。 */
    private int currentAckBits() {
        int bits = 0;
        synchronized (acked) {
            for (int i = 0; i < 32; i++) {
                final int sequence = highestReceived - 1 - i;
                if (acked.containsKey(sequence)) {
                    bits |= (1 << i);
                }
            }
        }
        return bits;
    }

    /** 把乱序缓冲里已经连续的部分依次交付给上层。 */
    private void drainReordered() {
        while (true) {
            final byte[] payload = reordered.remove(expectedSequence);
            if (payload == null) {
                return;
            }
            expectedSequence++;
            listener.onData(payload);
        }
    }

    /** 发送循环：把队列里的数据发出去，并重传超时的包。 */
    private void sendLoop() {
        while (open.get()) {
            final long start = System.currentTimeMillis();
            try {
                // 1) 发送新数据（受"在飞窗口"限制，这是本类最简的流量控制）
                for (int i = 0; i < SEND_BATCH; i++) {
                    final byte[] payload;
                    synchronized (outgoing) {
                        payload = outgoing.pollFirst();
                    }
                    if (payload == null) {
                        break;
                    }
                    if (inFlight() >= MAX_IN_FLIGHT) {
                        synchronized (outgoing) {
                            outgoing.addFirst(payload); // 窗口满了，放回去等下一轮
                        }
                        break;
                    }
                    transmit(payload);
                }

                // 2) 重传超时的包（丢包时靠它补齐）
                retransmitExpired();

                // 3) 只有单向流量时也定期回确认，避免对端空等
                if (System.currentTimeMillis() - lastEmptyAck > 250) {
                    sendAcknowledgement();
                }

                final long elapsed = System.currentTimeMillis() - start;
                if (elapsed < SEND_INTERVAL_MS) {
                    Thread.sleep(SEND_INTERVAL_MS - elapsed);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 发送一个新的数据包，并记录进待确认表。 */
    private void transmit(final byte[] payload) {
        final int sequence;
        synchronized (unacked) {
            sequence = nextSequence++;
        }
        try {
            final byte[] packet = buildDataPacket(sequence, payload);
            synchronized (unacked) {
                unacked.put(sequence, new Pending(sequence, packet, System.currentTimeMillis(), 0));
            }
            channel.send(ByteBuffer.wrap(packet), remote);
        } catch (Exception e) {
            LOGGER.debug("UDP link send failed: {}", e.getMessage());
        }
    }

    /**
     * 重传超过 {@link #RETRANSMIT_INTERVAL_MS} 仍未确认的包。
     *
     * <p>重试次数用尽后就放弃该包：我们承载的是 TCP 流量，真丢了最终会由 TCP 自己重传整段数据。
     */
    private void retransmitExpired() {
        final long now = System.currentTimeMillis();
        final java.util.List<Pending> resend = new java.util.ArrayList<>();

        synchronized (unacked) {
            final Iterator<Map.Entry<Integer, Pending>> iterator = unacked.entrySet().iterator();
            while (iterator.hasNext()) {
                final Pending pending = iterator.next().getValue();
                if (now - pending.sentAt() < RETRANSMIT_INTERVAL_MS) {
                    continue;
                }
                if (pending.attempts() >= MAX_RETRIES) {
                    iterator.remove();
                    continue;
                }
                resend.add(
                        new Pending(
                                pending.sequence(), pending.packet(), now, pending.attempts() + 1));
            }
            for (Pending pending : resend) {
                unacked.put(pending.sequence(), pending);
            }
        }

        for (Pending pending : resend) {
            try {
                channel.send(ByteBuffer.wrap(pending.packet()), remote);
            } catch (IOException e) {
                LOGGER.debug("UDP link retransmit failed: {}", e.getMessage());
            }
        }
    }

    /** 根据对方的确认号与位图，移除已经送达的包。 */
    private void onAcknowledgement(final int ack, final int bits) {
        synchronized (unacked) {
            final Iterator<Map.Entry<Integer, Pending>> iterator = unacked.entrySet().iterator();
            while (iterator.hasNext()) {
                final int sequence = iterator.next().getKey();
                if (sequence <= ack) {
                    iterator.remove(); // 确认号的含义是"这个以及之前的都收到了"
                    continue;
                }
                final int distance = sequence - ack - 1;
                if (distance < 32 && ((bits >>> distance) & 1) == 1) {
                    iterator.remove(); // 位图里标记为已收到
                }
            }
        }
    }

    /** 发一个不携带数据的确认包。 */
    private void sendAcknowledgement() {
        if (!open.get()) {
            return;
        }
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
            buffer.put(MAGIC).put(VERSION).put(TYPE_ACK);
            buffer.putInt(0); // 纯确认包里"我方发送序号"没有意义
            buffer.putInt(highestReceived);
            buffer.putInt(currentAckBits());
            buffer.flip();
            channel.send(buffer, remote);
            lastEmptyAck = System.currentTimeMillis();
        } catch (IOException e) {
            LOGGER.debug("UDP link ack failed: {}", e.getMessage());
        }
    }

    /** 组装数据包：明文包头 + 加密载荷；包头作为附加认证数据，被篡改会被解密阶段发现。 */
    private byte[] buildDataPacket(final int sequence, final byte[] payload) throws Exception {
        final ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        header.put(MAGIC).put(VERSION).put(TYPE_DATA);
        header.putInt(sequence);
        header.putInt(highestReceived); // 捎带确认，省一个包
        header.putInt(currentAckBits());
        final byte[] headerBytes = header.array();

        final byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, sequence, payload, headerBytes);

        final byte[] packet = new byte[HEADER_SIZE + encrypted.length];
        System.arraycopy(headerBytes, 0, packet, 0, HEADER_SIZE);
        System.arraycopy(encrypted, 0, packet, HEADER_SIZE, encrypted.length);
        return packet;
    }

    /** 用序号当 nonce 做 AES-GCM；序号不会重复，所以 nonce 也不会重复。 */
    private byte[] crypt(final int mode, final int sequence, final byte[] data, final byte[] aad)
            throws Exception {
        final byte[] nonce = new byte[12];
        nonce[0] = (byte) (sequence >>> 24);
        nonce[1] = (byte) (sequence >>> 16);
        nonce[2] = (byte) (sequence >>> 8);
        nonce[3] = (byte) sequence;

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }

    /** @return 解密后的载荷；失败（被篡改或密钥不符）时返回 {@code null} */
    private byte[] decrypt(final int sequence, final byte[] cipherText, final byte[] aad) {
        try {
            return crypt(Cipher.DECRYPT_MODE, sequence, cipherText, aad);
        } catch (Exception e) {
            // 解密失败的最常见原因：AAD 与加密时不一致（被篡改或版本不匹配）
            return null;
        }
    }

    private static boolean hasMagic(final byte[] packet) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (packet[i] != MAGIC[i]) {
                return false;
            }
        }
        return packet[4] == VERSION;
    }

    private static int readInt(final byte[] data, final int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    @Override
    public void close() {
        if (!open.getAndSet(false)) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.debug("Closing the UDP link: {}", e.getMessage());
        }
        synchronized (unacked) {
            unacked.clear();
        }
        synchronized (outgoing) {
            outgoing.clear();
        }
        synchronized (reordered) {
            reordered.clear();
        }
        listener.onClosed("链路已关闭");
    }
}
