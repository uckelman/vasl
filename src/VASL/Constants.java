package VASL;

import VASSAL.Info;
import VASSAL.build.Buildable;
import VASSAL.build.Builder;
import VASSAL.build.GameModule;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.util.List;

/**
 * Container for storing magical literal values that are valid across the entire module.
 */
public class Constants implements Buildable {

    public static final String BOARD_DIR = "boardURL";
    public static final String REMOTE_BOARD_URL = "https://github.com/vasl-developers/vasl-boards.git";

    public String getVersion() {
        return GameModule.getGameModule().getGameVersion();
    }

    public void build(Element e) {
        Builder.build(e, this);
    }

    public void addTo(Buildable parent) {
        // we do nothing as we've been already added by GameModule
    }

    public void add(Buildable child) {
        // we do nothing as this class does not accept children.
    }

    public Element getBuildElement(Document doc) {
        return doc.createElement(getClass().getName());
    }

    /**
     * Answers the folder where the boards are stored.
     * This folder will be created if it does not exist upon calling this method.
     * @return The folder where the boards are stored.
     */
    public File getBoardFolder() {
        File baseDir = Info.getHomeDir();
        if (!baseDir.isDirectory() && baseDir.exists()) {
            throw new RuntimeException("Could not locate base directory");
        }
        File target = new File(baseDir, "vaslboards");
        if (!target.exists()) {
            target.mkdir();
        }
        return target;
    }

    /**
     * Returns the singleton instances configured in the buildFile.
     * @return the singleton instances configured in the buildFile.
     */
    public static Constants getConstants() {
        return GameModule.getGameModule().getComponentsOf(Constants.class).get(0);
    }
}