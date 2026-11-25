package dev.melo.kokoro;

import java.nio.file.Path;
import java.util.Arrays;

/** Executes both JNI entry points against an installed official model pack on a host CPU. */
public final class KokoroPreparedPhraseIntegration {
    public static void main(String[] args) {
        Path pack = Path.of(args[1]);
        String tokens = pack.resolve("tokens.txt").toString();
        String dictionary = pack.resolve("lexicon-us-en.txt").toString();
        try (TimedKokoro model = new TimedKokoro(args[0], pack.resolve("voices.bin").toString(), tokens, dictionary, 2);
             KokoroFrontend other = new KokoroFrontend(tokens, pack.resolve("lexicon-gb-en.txt").toString())) {
            String phrase = "the voice is ready.";
            for (float rate : new float[]{1f, 1.25f}) {
                KokoroFrontend.EncodedPhrase input = model.encode(phrase);
                TimedKokoro.Audio original = model.generate(phrase, 0, rate);
                Arrays.fill(input.tokenIds, -1);
                Arrays.fill(input.wordTokens, -1);
                TimedKokoro.Audio prepared = model.generate(input, 0, rate);
                if (!Arrays.equals(original.durations, prepared.durations) ||
                        !Arrays.equals(original.wordTokens, prepared.wordTokens) ||
                        original.samples.length != prepared.samples.length || prepared.samples.length < 2400 ||
                        !model.evidence().validatedAudio) {
                    throw new AssertionError("Prepared input changed the word timing or produced invalid speech");
                }
                System.out.println("Prepared and text paths agreed for " + prepared.samples.length + " frames at speed " + rate);
            }
            // Prepared and text entry points retain the same native input-byte count.
            // Currently admitted frontend text is ASCII; this is an ownership contract.
            KokoroFrontend.EncodedPhrase preparedInput = model.encode(phrase);
            int originalBytes;
            try {
                model.generate(phrase, 0, Float.NaN);
                throw new AssertionError("Invalid speed was accepted for text input");
            } catch (TimedKokoro.Failure expected) {
                if (!"FRONTEND_INVALID_SPEED".equals(expected.code)) throw expected;
                originalBytes = expected.inputBytes;
            }
            try {
                model.generate(preparedInput, 0, Float.NaN);
                throw new AssertionError("Invalid speed was accepted for prepared input");
            } catch (TimedKokoro.Failure expected) {
                if (!"FRONTEND_INVALID_SPEED".equals(expected.code)) throw expected;
                if (expected.inputBytes != originalBytes || originalBytes != phrase.length()) {
                    throw new AssertionError("Prepared input lost the native encoded byte count");
                }
            }
            // Unsupported separators still fail closed, with actual native bytes.
            String accented = "the café is ready.";
            try {
                model.encode(accented);
                throw new AssertionError("Unsupported accented separator was accepted");
            } catch (TimedKokoro.Failure expected) {
                if (!"FRONTEND_INVALID_SEPARATOR".equals(expected.code)) throw expected;
                if (expected.inputBytes <= accented.length()) {
                    throw new AssertionError("Frontend refusal lost the native encoded byte count");
                }
            }
            try {
                model.generate(other.encode(phrase), 0, 1f);
                throw new AssertionError("Foreign pronunciation dictionary accepted");
            } catch (IllegalArgumentException expected) {
                if (!expected.getMessage().contains("vocabulary")) throw expected;
            }
        }
    }
}
