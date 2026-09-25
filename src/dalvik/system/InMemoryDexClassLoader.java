package dalvik.system;

import java.nio.ByteBuffer;

public class InMemoryDexClassLoader extends ClassLoader {
    public InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        super(parent);
    }
    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, String librarySearchPath, ClassLoader parent) {
        super(parent);
    }
}