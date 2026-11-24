package pl.mtsit.packguard;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import java.util.List;

public final class WindowsProcessGuard {
    private static final int BASIC_LIMIT_INFORMATION = 2;
    private static final int ACTIVE_PROCESS_LIMIT = 0x00000008;

    private WindowsProcessGuard() {}

    static void install() {
        if (!Platform.isWindows()) return;
        restrictCurrentProcess();
        System.out.println("[PackGuard] Windows child-process limit active");
    }

    static void restrictCurrentProcess() {
        Kernel kernel = Native.load("kernel32", Kernel.class);
        Pointer job = kernel.CreateJobObjectW(null, null);
        if (job == null) throw failure("create job");
        try {
            BasicLimits limits = new BasicLimits();
            limits.flags = ACTIVE_PROCESS_LIMIT;
            limits.activeProcesses = 1;
            limits.write();
            if (!kernel.SetInformationJobObject(job, BASIC_LIMIT_INFORMATION, limits.getPointer(), limits.size()))
                throw failure("set active-process limit");
            if (!kernel.AssignProcessToJobObject(job, kernel.GetCurrentProcess()))
                throw failure("assign Minecraft to job");
        } finally {
            if (!kernel.CloseHandle(job))
                System.err.println("[PackGuard] Could not close the Windows job handle, error " + Native.getLastError());
        }
    }

    private static IllegalStateException failure(String operation) {
        return new IllegalStateException("PackGuard cannot " + operation + " (Windows error " + Native.getLastError() + ")");
    }

    public interface Kernel extends StdCallLibrary {
        Pointer CreateJobObjectW(Pointer attributes, Pointer name);
        Pointer GetCurrentProcess();
        boolean SetInformationJobObject(Pointer job, int informationClass, Pointer information, int size);
        boolean AssignProcessToJobObject(Pointer job, Pointer process);
        boolean CloseHandle(Pointer handle);
    }

    public static final class BasicLimits extends Structure {
        public long processTime;
        public long jobTime;
        public int flags;
        public Pointer minWorkingSet;
        public Pointer maxWorkingSet;
        public int activeProcesses;
        public Pointer affinity;
        public int priority;
        public int scheduling;

        protected List<String> getFieldOrder() {
            return List.of("processTime", "jobTime", "flags", "minWorkingSet", "maxWorkingSet",
                    "activeProcesses", "affinity", "priority", "scheduling");
        }
    }
}
