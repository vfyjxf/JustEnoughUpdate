package dev.vfyjxf.jeup;


import dev.vfyjxf.jeup.utils.ClassProfiler;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

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

    @SuppressWarnings("unused")
    public static URL redirectUrl(URL url) throws MalformedURLException {
        return new URL("file:/dev/noupdate$");
    }

}
