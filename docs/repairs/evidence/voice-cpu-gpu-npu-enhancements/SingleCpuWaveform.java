import dev.melo.kokoro.TimedKokoro;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** One existing-library inference; output is read by the actual current C++ validator. */
public final class SingleCpuWaveform {
    public static void main(String[] args) throws Exception {
        Path pack = Path.of(args[0]);
        try (TimedKokoro model = new TimedKokoro(pack.resolve("model.int8.onnx").toString(),
                pack.resolve("voices.bin").toString(), pack.resolve("tokens.txt").toString(),
                pack.resolve("lexicon-us-en.txt").toString(), 2)) {
            TimedKokoro.Audio audio = model.generate("clear speech.", 0, 1f);
            if (audio.samples.length <= 0 || audio.samples.length > 720000 || audio.durations.length > 511)
                throw new AssertionError("Unbounded waveform");
            ByteBuffer buffer = ByteBuffer.allocate(8 + audio.samples.length * 4 + audio.durations.length * 8)
                    .order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(audio.samples.length).putInt(audio.durations.length);
            for (float sample : audio.samples) buffer.putFloat(sample);
            for (long duration : audio.durations) buffer.putLong(duration);
            Files.write(Path.of(args[1]), buffer.array());
            System.out.println("Cached CPU JNI generated one fixed-input raw waveform");
        }
    }
}
