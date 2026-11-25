import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;
import org.objectweb.asm.*;

/** Reads already-compiled bytecode. No repository classes are loaded or executed. */
class CompilerCalls {
    static String key(String owner, String name, String descriptor) {
        return owner + "." + name + descriptor;
    }

    static String fingerprint(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static void main(String[] roots) throws IOException {
        var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        for (String root : roots) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path path : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("Oversize class");
                    var reader = new ClassReader(Files.readAllBytes(path));
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        final String owner = reader.getClassName();
                        String source;
                        @Override public void visitSource(String name, String debug) { source = name; }
                        boolean emit(String... fields) {
                            for (String s : fields) {
                                if (s == null || s.indexOf('\t') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) return false;
                            }
                            try { out.write(String.join("\t", fields)); out.newLine(); return true; }
                            catch (IOException e) { throw new UncheckedIOException(e); }
                        }
                        @Override public MethodVisitor visitMethod(int flags, String name, String descriptor, String signature, String[] exceptions) {
                            if (source == null) return null;
                            String caller = key(owner, name, descriptor);
                            if (!emit("M", caller, owner, source)) return null;
                            // Isolate each method's constant pool. Exclude debug tables and frames so
                            // unrelated edits and line moves do not change the method fingerprint.
                            var isolated = new ClassWriter(0);
                            isolated.visit(reader.readUnsignedShort(6), Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
                            var body = isolated.visitMethod(flags, name, descriptor, signature, exceptions);
                            return new MethodVisitor(Opcodes.ASM9, body) {
                                int line;
                                int firstLine = Integer.MAX_VALUE;
                                @Override public void visitLineNumber(int n, Label at) {
                                    line = n;
                                    if (n > 0) firstLine = Math.min(firstLine, n);
                                }
                                @Override public void visitLocalVariable(String name, String desc, String sig, Label start, Label end, int slot) {}
                                @Override public void visitMethodInsn(int op, String target, String name, String desc, boolean itf) {
                                    super.visitMethodInsn(op, target, name, desc, itf);
                                    emit("C", key(target, name, desc), Integer.toString(line));
                                }
                                @Override public void visitEnd() {
                                    super.visitEnd();
                                    isolated.visitEnd();
                                    emit("L", Integer.toString(firstLine == Integer.MAX_VALUE ? 0 : firstLine), fingerprint(isolated.toByteArray()));
                                }
                            };
                        }
                    }, ClassReader.SKIP_FRAMES);
                }
            }
        }
        out.flush();
    }
}
