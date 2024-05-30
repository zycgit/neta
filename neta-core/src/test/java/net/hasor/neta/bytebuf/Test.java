//package net.hasor.neta.bytebuf;
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.FileNotFoundException;
//import java.io.IOException;
//import java.nio.channels.AsynchronousFileChannel;
//import java.nio.channels.FileChannel;
//import java.nio.file.Paths;
//import java.nio.file.StandardOpenOption;
//
//public class Test {
//    @org.junit.Test
//    public void writeByteTest01() throws IOException {
//
//        AsynchronousFileChannel fileChannel = AsynchronousFileChannel.open(Paths.get(""), StandardOpenOption.READ);
//        AsynchronousFileChannel truncate = fileChannel.truncate(111);
//        truncate.write()
//
//        File file = new File("d:\\test\\write.txt");
//        FileInputStream inputStream = new FileInputStream(file);
//        //创建管道，把文件放入通道
//        FileChannel fileChannel = inputStream.getChannel();
//
//        //=========== 创建写通道 ===========
//        //创建文件
//        FileOutputStream outputStream = new FileOutputStream("d:\\test\\write22.txt");
//        //创建Channel2，把文件放入通道
//        FileChannel fileChannel2 = outputStream.getChannel();
//    }
//}