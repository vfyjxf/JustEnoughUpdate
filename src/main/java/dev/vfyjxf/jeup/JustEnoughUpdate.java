package dev.vfyjxf.jeup;


import dev.vfyjxf.jeup.utils.ClassProfiler;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

@Mod(modid = "jeup")
public class JustEnoughUpdate {

    public static final Logger LOG = LogManager.getLogger();

    public JustEnoughUpdate() {
        ClassProfiler.runScan();
    }

    @SuppressWarnings("unused")
    public static void throwIOException() throws IOException {
        throw new IOException("Can't access network because of JustEnoughUpdate");
    }

}
