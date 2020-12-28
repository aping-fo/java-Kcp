package com.backblaze.erasure;

import com.backblaze.erasure.fec.*;
import io.netty.buffer.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试fec功能
 * Created by JinMiao
 * 2018/6/21.
 */
public class FecTest {


    static ByteBufAllocator pooledByteBufAllocator = new UnpooledByteBufAllocator(false);

    public static void main(String[] args) {
        //ByteBuf byteBuf = pooledByteBufAllocator.directBuffer(10);
        //byteBuf.duplicate();
        //System.out.println(byteBuf.refCnt());
        //ByteBuf newByteBuf = byteBuf.copy();
        //System.out.println(newByteBuf.refCnt());
        //System.out.println(byteBuf.refCnt());
        //newByteBuf.release();
        //System.out.println(newByteBuf.refCnt());
        //System.out.println(byteBuf.refCnt());


        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                    getDirectBufSize();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }).start();


        //testOOM();
        //encodeOOM();
        new Thread(() -> runtask()).start();
    }

    static Field maxMemoryField = null;
    static Field reservedMemoryField = null;

    public static void getDirectBufSize() throws NoSuchFieldException, ClassNotFoundException, IllegalAccessException {
        Class bitClass = null;
        if (maxMemoryField == null) {
            bitClass = Class.forName("java.nio.Bits");
            maxMemoryField = bitClass.getDeclaredField("maxMemory");
            reservedMemoryField = bitClass.getDeclaredField("reservedMemory");
            maxMemoryField.setAccessible(true);
            reservedMemoryField.setAccessible(true);
        }
        System.out.println("maxDirectbuf: " + maxMemoryField.get(bitClass));

        System.out.println("usedDirectbuf: " + reservedMemoryField.get(bitClass));
    }

    public static void encodeOOM() {
        int data = 10;
        int part = 3;
        ReedSolomon reedSolomon = ReedSolomon.create(data, part);
        FecEncode fecEncode = new FecEncode(0, reedSolomon, 1500);
        FecDecode fecDecode = new FecDecode((data + part) * 3, reedSolomon, 1500);
        while (true) {
            List<ByteBuf> byteBufList = new ArrayList<>();
            List<FecTestPackage> byteBufs = buildBytebuf(data, 1500);
            for (int i = 0; i < byteBufs.size(); i++) {
                ByteBuf[] encodeResult = fecEncode.encode(byteBufs.get(i).getByteBuf());
                if (encodeResult != null) {
                    for (int i1 = 0; i1 < encodeResult.length; i1++) {
                        byteBufList.add(encodeResult[i1]);
                    }
                }
                byteBufList.add(byteBufs.get(i).getByteBuf());
            }
            for (ByteBuf byteBuf : byteBufList) {
                byteBuf.release();
            }
        }
    }


    public static void decodeOOM() {

    }


    public static void testOOM() {
        int data = 10;
        int part = 3;

        ReedSolomon reedSolomon = ReedSolomon.create(data, part);
        FecEncode fecEncode = new FecEncode(0, reedSolomon, 1500);
        FecDecode fecDecode = new FecDecode((data + part) * 3, reedSolomon, 1500);

        ArrayBlockingQueue<ByteBuf> queue = new ArrayBlockingQueue<>(150);
        AtomicLong atomicLong = new AtomicLong();
        //生产
        new Thread(() -> {
            try {
                while (true) {
                    List<ByteBuf> byteBufList = new ArrayList<>();
                    List<FecTestPackage> byteBufs = buildBytebuf(data, 1500);
                    for (int i = 0; i < byteBufs.size(); i++) {
                        ByteBuf[] encodeResult = fecEncode.encode(byteBufs.get(i).getByteBuf());
                        if (encodeResult != null) {
                            for (int i1 = 0; i1 < encodeResult.length; i1++) {
                                byteBufList.add(encodeResult[i1]);
                            }
                        }
                        byteBufList.add(byteBufs.get(i).getByteBuf());
                    }

                    int drop = random.nextInt(data + part);

                    //模拟丢数据
                    for (int i = 0; i < drop; i++) {
                        int dropIndex = random.nextInt(byteBufList.size());
                        byteBufList.get(dropIndex).release();
                        byteBufList.remove(dropIndex);
                    }
                    //打乱顺序
                    Collections.shuffle(byteBufList);
                    for (ByteBuf byteBuf : byteBufList) {
                        if (!queue.offer(byteBuf)) {
                            byteBuf.release();
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println(atomicLong.get());
                t.printStackTrace();
            }
        }).start();

        //消费
        new Thread(() -> {
            try {
                int i = 0;
                while (true) {
                    List<ByteBuf> byteBufs = new ArrayList<>();
                    while (true) {
                        ByteBuf byteBuf = queue.poll();
                        if (byteBuf == null) {
                            break;
                        }
                        byteBufs.add(byteBuf);
                    }
                    if (byteBufs.isEmpty()) {
                        continue;
                    }
                    try {
                        Thread.sleep(10);

                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }


                    Collections.shuffle(byteBufs);

                    for (ByteBuf byteBuf : byteBufs) {
                        i++;

                        if (i % (100000) == 0) {
                            System.out.println(Snmp.snmp.toString());
                        }

                        FecPacket fecPacket = FecPacket.newFecPacket(byteBuf);
                        atomicLong.set(fecPacket.getSeqid());
                        List<ByteBuf> dencodeResult = fecDecode.decode(fecPacket);
                        if (dencodeResult != null) {
                            for (ByteBuf buf : dencodeResult) {
                                //System.out.println("refa "+buf.refCnt());
                                buf.release();
                            }
                        }
                        //System.out.println("ref "+byteBuf.refCnt());
                        byteBuf.release();
                    }
                }
            } catch (Throwable t) {
                System.out.println(atomicLong.get());
                t.printStackTrace();
            }
        }).start();
    }


    private static void runtask() {
        int data = 10;
        int part = 3;
        int mtu = 1500;

        ReedSolomon reedSolomon = ReedSolomon.create(data, part);

        FecEncode fecEncode = new FecEncode(0, reedSolomon, mtu);
        FecDecode fecDecode = new FecDecode((data + part) * 3, reedSolomon, mtu);
        for(;;) {
            try {
                //是否能恢复
                int dropCount = random.nextInt(data) + 1;
                boolean canDecode = dropCount <= part;
                List<FecTestPackage> byteBufs = buildBytebuf(data, mtu);
                Map<Integer,ByteBuf> dropMap = new HashMap<>();

                //随机丢包
                randomDrop(byteBufs, dropCount, canDecode,dropMap);

                List<FecTestPackage> byteBufList = new ArrayList<>();
                for (FecTestPackage byteBuf : byteBufs) {
                    ByteBuf[] encodeResult = fecEncode.encode(byteBuf.getByteBuf());
                    if (encodeResult != null) {
                        for (int i1 = 0; i1 < encodeResult.length; i1++) {
                            byteBufList.add(new FecTestPackage(encodeResult[i1]));
                        }
                    }
                    byteBufList.add(byteBuf);
                }

                //乱序
                Collections.shuffle(byteBufList);

                //恢复数据包
                List<ByteBuf> dencodeResult = null;
                for (FecTestPackage byteBuf : byteBufList) {
                    if (byteBuf.isDrop()) {
                        continue;
                    }
                    FecPacket fecPacket = FecPacket.newFecPacket(byteBuf.getByteBuf());
                    dencodeResult = fecDecode.decode(fecPacket);
                    if (dencodeResult != null){
                        break;
                    }
                }
                if (canDecode) {
                    Map<Integer,ByteBuf> recoverMap = new HashMap();
                    for (ByteBuf byteBuf : dencodeResult) {
                        int id = byteBuf.getInt(0);
                        recoverMap.put(id,byteBuf);
                    }
                    for (ByteBuf value : dropMap.values()) {
                        int dropId = value.getInt(Fec.fecHeaderSizePlus2);
                        ByteBuf recoverByteBuf = recoverMap.get(dropId);
                        if(ByteBufUtil.equals(recoverByteBuf,0,value,Fec.fecHeaderSizePlus2,recoverByteBuf.readableBytes())){
                            //System.out.println("恢复了" + recoverByteBuf.getInt(0));
                        }else{
                            System.out.println("恢复异常");
                        }
                    }
                }

                //释放资源
                for (FecTestPackage byteBuf : byteBufList) {
                    byteBuf.getByteBuf().release();
                }
                if (dencodeResult != null) {
                    for (ByteBuf byteBuf : dencodeResult) {
                        byteBuf.release();
                    }

                }
            }catch (Throwable e){
                e.printStackTrace();
            }
        }

        //fecEncode.release();
        //fecDecode.release();

    }


    static final Random random = new Random();

    private static void randomDrop(List<FecTestPackage> byteBufs, int dropCount, boolean canDecode,Map<Integer,ByteBuf> dropMap) {
        for (; ; ) {
            if (dropCount == 0) {
                break;
            }
            int dropIndex = random.nextInt(byteBufs.size());
            FecTestPackage fecTestPackage = byteBufs.get(dropIndex);
            if (!fecTestPackage.isDrop()) {
                int dropId = fecTestPackage.getByteBuf().getInt(Fec.fecHeaderSizePlus2);
                if (canDecode) {
                    //System.out.println("准备丢弃" + dropId);
                }
                dropMap.put(dropId,fecTestPackage.getByteBuf());
                fecTestPackage.setDrop(true);
                dropCount--;
            }
        }
    }

    private static final AtomicInteger id = new AtomicInteger();

    private static List<FecTestPackage> buildBytebuf(int dataShardCount, int mtu) {
        List<FecTestPackage> byteBufs = new ArrayList<>();
        for (int i = 0; i < dataShardCount; i++) {
            ByteBuf byteBuf = pooledByteBufAllocator.buffer(mtu);
            byteBuf.writeBytes(new byte[Fec.fecHeaderSizePlus2]);
            int ids = id.incrementAndGet();
            byteBuf.writeInt(ids);
            int size = random.nextInt(mtu - Fec.fecHeaderSizePlus2 -4) + 1;
            for (int i1 = 0; i1 < size; i1++) {
                byteBuf.writeByte(random.nextInt(127));
            }
            FecTestPackage fecTestPackage = new FecTestPackage(byteBuf);
            fecTestPackage.setId(ids);
            byteBufs.add(fecTestPackage);
        }

        return byteBufs;
    }
}
