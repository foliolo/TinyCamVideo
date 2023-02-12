package video

import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacv.*
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 *
 * @author Dmitriy Gerashenko <d.a.gerashenko></d.a.gerashenko>@gmail.com>
 */
object FFmpegStreamingTimeout {
    private const val TIMEOUT = 10 // In seconds.

    @JvmStatic
    fun main(args: Array<String>) {
        rtspStreamingTest(args[0])
//        testWithCallback(args[0]); // This is not working properly. It's just for test.
    }

    private fun rtspStreamingTest(url: String) {
        try {
            val grabber = FFmpegFrameGrabber(url)
            /**
             * "rw_timeout" - IS IGNORED when a network cable have been
             * unplugged before a connection but the option takes effect after a
             * connection was established.
             *
             * "timeout" - works fine.
             */
            grabber.setOption(
                TimeoutOption.TIMEOUT.key, (TIMEOUT * 1000000).toString()
            ) // In microseconds.
            grabber.start()
            var frame: Frame
            /**
             * When network is disabled (before grabber was started) grabber
             * throws exception: "org.bytedeco.javacv.FrameGrabber$Exception:
             * avformat_open_input() error -138: Could not open input...".
             *
             * When connections is lost (after a few grabbed frames)
             * grabber.grab() returns null without exception.
             */
            while (grabber.grab().also { frame = it } != null) {
                println("frame grabbed at " + grabber.timestamp)

                val frame = grabber.grabImage()
                try {
                    writeFrameToFile(frame, "frame" + System.currentTimeMillis())
                } catch (ex: Exception) {
                    println(ex.message)
                }
                break
            }
            println("loop end with frame: $frame")
        } catch (ex: FrameGrabber.Exception) {
            println("exception: $ex")
        }
        println("end")
    }

    @Throws(IOException::class)
    private fun writeFrameToFile(frame: Frame, fileNamePrefix: String): File {
        val frameFile = File("$fileNamePrefix.jpg")
        val image = Java2DFrameConverter().convert(frame)
        ImageIO.write(image, "jpg", frameFile)
        return frameFile
    }

    private fun testWithCallback(url: String) {
        try {
            val grabber = FFmpegFrameGrabber(url)
            /**
             * grabber.getFormatContext() is null before grabber.start().
             *
             * But if network is disabled grabber.start() will never return.
             *
             * That's why interrupt_callback not suitable for "network disabled
             * case".
             */
            grabber.start()
            val interruptFlag = AtomicBoolean(false)
            val cp: AVIOInterruptCB.Callback_Pointer = object : AVIOInterruptCB.Callback_Pointer() {
                override fun call(pointer: Pointer?): Int {
                    // 0 - continue, 1 - exit
                    val interruptFlagInt = if (interruptFlag.get()) 1 else 0
                    println("callback, interrupt flag == $interruptFlagInt")
                    return interruptFlagInt
                }
            }
            val oc: AVFormatContext = grabber.formatContext
            avformat_alloc_context()
            val cb = AVIOInterruptCB()
            cb.callback(cp)
            oc.interrupt_callback(cb)
            Thread {
                try {
                    TimeUnit.SECONDS.sleep(TIMEOUT.toLong())
                    interruptFlag.set(true)
                    println("interrupt flag was changed")
                } catch (ex: InterruptedException) {
                    println("exception in interruption thread: $ex")
                }
            }.start()
            var frame: Frame? = null
            /**
             * On one of my RTSP cams grabber stops calling callback on
             * connection lost. I think it's has something to do with message:
             * "[swscaler @ 0000000029af49e0] deprecated pixel format used, make
             * sure you did set range correctly".
             *
             * So there is at least one case when grabber stops calling
             * callback.
             */
            while (grabber.grab().also { frame = it } != null) {
                println("frame grabbed at " + grabber.timestamp)
            }
            println("loop end with frame: $frame")
        } catch (ex: FrameGrabber.Exception) {
            println("exception: $ex")
        }
        println("end")
    }

    /**
     * There is no universal option for streaming timeout. Each of protocols has
     * its own list of options.
     */
    private enum class TimeoutOption {
        /**
         * Depends on protocol (FTP, HTTP, RTMP, RTSP, SMB, SSH, TCP, UDP, or UNIX).
         * http://ffmpeg.org/ffmpeg-all.html
         *
         * Specific for RTSP:
         * Set socket TCP I/O timeout in microseconds.
         * http://ffmpeg.org/ffmpeg-all.html#rtsp
         */
        TIMEOUT,

        /**
         * Protocols
         *
         * Maximum time to wait for (network) read/write operations to complete,
         * in microseconds.
         *
         * http://ffmpeg.org/ffmpeg-all.html#Protocols
         */
        RW_TIMEOUT;

        val key: String
            get() = toString().lowercase(Locale.getDefault())
    }
}