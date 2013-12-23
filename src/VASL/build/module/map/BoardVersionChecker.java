/*
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Library General Public License for more details.
 *
 * You should have received a copy of the GNU Library General Public
 * License along with this library; if not, copies are available
 * at http://www.opensource.org.
 */
package VASL.build.module.map;

import VASL.build.module.map.boardPicker.ASLBoard;
import VASL.build.module.map.boardPicker.Overlay;
import VASSAL.build.AbstractBuildable;
import VASSAL.build.Buildable;
import VASSAL.build.GameModule;
import VASSAL.build.module.GameComponent;
import VASSAL.build.module.Map;
import VASSAL.build.module.ServerConnection;
import VASSAL.build.module.map.boardPicker.Board;
import VASSAL.command.Command;
import VASSAL.configure.BooleanConfigurer;
import VASSAL.configure.StringConfigurer;
import VASSAL.i18n.Resources;
import VASSAL.tools.PropertiesEncoder;
import VASSAL.tools.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import javax.swing.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Vector;
import java.util.concurrent.ExecutionException;

/**
 * The BoardVersionChecker will check for new boards in our repository - and download them.
 */
public class BoardVersionChecker extends AbstractBuildable implements GameComponent, PropertyChangeListener {
    private static final String BOARD_VERSION_URL = "boardVersionURL";
    private static final String OVERLAY_VERSION_URL = "overlayVersionURL";
    private static final String BOARD_PAGE_URL = "boardPageURL";
    private static final String BOARD_VERSIONS = "boardVersions";
    private static final String OVERLAY_VERSIONS = "overlayVersions";
    private static final String AUTO_SYNCH_BOARDS = "autoSynchBoards";
    // our board repository
    private final static String REMOTE_URL = "https://github.com/vasl-developers/vasl-boards.git";
    private String boardVersionURL;
    private String overlayVersionURL;
    private String boardPageURL;
    private boolean autoSynchBoards;
    private Map map;
    private Properties boardVersions;
    private Properties overlayVersions;

    public String[] getAttributeNames() {
        return new String[]{BOARD_VERSION_URL, OVERLAY_VERSION_URL, BOARD_PAGE_URL, AUTO_SYNCH_BOARDS};
    }

    public String getAttributeValueString(String key) {
        if (BOARD_VERSION_URL.equals(key)) {
            return boardVersionURL;
        } else if (OVERLAY_VERSION_URL.equals(key)) {
            return overlayVersionURL;
        } else if (BOARD_PAGE_URL.equals(key)) {
            return boardPageURL;
        } else if (AUTO_SYNCH_BOARDS.equals(key)) {
            return AUTO_SYNCH_BOARDS;
        }
        return null;
    }

    public void setAttribute(String key, Object value) {
        if (BOARD_VERSION_URL.equals(key)) {
            boardVersionURL = (String) value;
        } else if (OVERLAY_VERSION_URL.equals(key)) {
            overlayVersionURL = (String) value;
        } else if (BOARD_PAGE_URL.equals(key)) {
            boardPageURL = (String) value;
        } else if (AUTO_SYNCH_BOARDS.equals(key)) {
            autoSynchBoards = Boolean.TRUE.equals(value);
        }
    }

    public void addTo(Buildable parent) {
        map = (Map) parent;
        GameModule.getGameModule().getGameState().addGameComponent(this);
        GameModule.getGameModule().getServer().addPropertyChangeListener(ServerConnection.CONNECTED, this);
        GameModule.getGameModule().getPrefs().addOption(null, new StringConfigurer(BOARD_VERSIONS, null));
        Properties p = readVersionList((String) GameModule.getGameModule().getPrefs().getValue(BOARD_VERSIONS));
        if (p != null) {
            boardVersions = p;
        }
        GameModule.getGameModule().getPrefs().addOption(null, new StringConfigurer(OVERLAY_VERSIONS, null));
        p = readVersionList((String) GameModule.getGameModule().getPrefs().getValue(OVERLAY_VERSIONS));
        if (p != null) {
            overlayVersions = p;
        }
        // add the configuration option for automatic board updates
        final BooleanConfigurer configurer = new BooleanConfigurer(AUTO_SYNCH_BOARDS, "Automatic board updates (restart required)?", Boolean.FALSE);
        GameModule.getGameModule().getPrefs().addOption(Resources.getString("Prefs.general_tab"), configurer);
        autoSynchBoards = Boolean.TRUE.equals(GameModule.getGameModule().getPrefs().getValue(AUTO_SYNCH_BOARDS));

        if (autoSynchBoards) {

            final File boardFolder = (File) GameModule.getGameModule().getPrefs().getValue("boardURL");

            // Thread to update the boards as a background task
            org.jdesktop.swingworker.SwingWorker<String, Void> boardUpdateThread = new org.jdesktop.swingworker.SwingWorker<String, Void>() {

                @Override
                protected String doInBackground() throws Exception {

                    // initialize the repository if necessary
                    boolean repositoryOK = initRepository(boardFolder.getPath());

                    if (repositoryOK) {
                        return updateBoards(boardFolder.getPath());
                    }
                    return "";
                }

                protected void done() {
                    String error = null;
                    try {
                        error = get();
                    } catch (InterruptedException e) {
                    } catch (ExecutionException e) {
                    } finally {
                        // just log a warning if something went wrong
                        if (error != null && !error.equals("")) {
                            GameModule.getGameModule().warn("There was an error updating the boards: " + error);
                        }
                    }
                }
            };

            // update the boards
            boardUpdateThread.execute();
        }
    }

    /**
     * Copies board updates from the VASL board archive to the boards directory set in the preferences
     *
     * @return an error message or an empty string if no error
     */
    private String updateBoards(String boardFolder) {

        if (boardFolder == null || boardFolder.equals("")) {

            GameModule.getGameModule().warn("Cannot update boards. Invalid board folder:  " + boardFolder);
            return "";
        }

        // Open the local repository and pull any new/updated boards
        File localRepro = new File(boardFolder + File.separator + ".git");
        GameModule.getGameModule().warn("Updating boards...");
        Repository repository = null;
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            repository = builder.setGitDir(localRepro)
                    .readEnvironment() // scan environment GIT_* variables
                    .findGitDir() // scan up the file system tree
                    .build();
            Git git = new Git(repository);
            git.pull().call();

            GameModule.getGameModule().warn("Board update complete");

        } catch (Exception e) {

            GameModule.getGameModule().warn("Unable to update the boards from " + REMOTE_URL);
            GameModule.getGameModule().warn("Board synchronization is corrupt. Please empty your boards folder and restart.");

        } finally {
            repository.close();
        }
        return "";
    }

    /**
     * Initialize the boards folder. It must be empty; if not show a message
     *
     * @param boardFolder the boards folder
     * @return true if there were no errors
     */
    private boolean initRepository(String boardFolder) {

        if (boardFolder == null || boardFolder.equals("")) {

            GameModule.getGameModule().warn("Cannot update boards. Invalid board folder:  " + boardFolder);
        }
        // see if the local repository exists in the boards directory, if not clone it
        File localRepro = null;
        try {
            localRepro = new File(boardFolder + File.separator + ".git");
            if (!localRepro.exists() || !localRepro.isDirectory()) {

                // if the boards directory is not empty give up
                File boardsDirectory = new File(boardFolder);
                if (boardsDirectory.list().length > 0) {
                    GameModule.getGameModule().warn("The boards directory must initially be empty to use automatic board updates.");
                    GameModule.getGameModule().warn("Please move your old boards to a different location or pick a new boards directory.");
                    return false;
                }
                // then clone
                GameModule.getGameModule().warn("Initializing board synchronization in " + boardFolder);
                GameModule.getGameModule().warn("Please wait for the synchronization to complete before using VASL (it will take a few minutes, after which you'll see a message that it's complete)");
                Git.cloneRepository()
                        .setURI(REMOTE_URL)
                        .setDirectory(new File(boardFolder))
                        .call();
            }
        } catch (GitAPIException e) {

            GameModule.getGameModule().warn("Unable to clone the boards repository: " + REMOTE_URL);
            GameModule.getGameModule().warn(e.toString());
            return false;
        }
        return true;
    }

    public Command getRestoreCommand() {
        return null;
    }

    public void setup(boolean gameStarting) {

        if (gameStarting) {
            if (boardVersions != null) {
                String info = "Using board(s): ";
                for (Board board : map.getBoards()) {
                    ASLBoard b = (ASLBoard) board;
                    info += b.getName() + "(v" + b.getVersion() + ") ";
                }
                GameModule.getGameModule().warn(info);
            }

            if (boardVersions != null && !autoSynchBoards) {
                Vector obsolete = new Vector();
                for (Board board : map.getBoards()) {
                    ASLBoard b = (ASLBoard) board;
                    String availableVersion = boardVersions.getProperty(b.getName(), b.getVersion());
                    if (!availableVersion.equals(b.getVersion())) {
                        obsolete.addElement(b.getName());
                    }
                }
                String msg = null;
                if (obsolete.size() == 1) {
                    String name = (String) obsolete.firstElement();
                    msg = "Version " + boardVersions.getProperty(name) + " of board " + name + " is now available.\n" + boardPageURL;
                } else if (obsolete.size() > 1) {
                    StringBuffer buff = new StringBuffer();
                    for (int i = 0, j = obsolete.size(); i < j; ++i) {
                        buff.append((String) obsolete.elementAt(i));
                        if (i < j - 2) {
                            buff.append(", ");
                        } else if (i < j - 1) {
                            buff.append(" and ");
                        }
                    }
                    msg = "New versions of boards " + buff + " are available.\n" + boardPageURL;
                }
                if (msg != null) {
                    final String message = msg;
                    Runnable runnable = new Runnable() {
                        public void run() {
                            JOptionPane.showMessageDialog(map.getView().getTopLevelAncestor(), message);
                        }
                    };
                    SwingUtilities.invokeLater(runnable);
                }
            }
            if (overlayVersions != null && !autoSynchBoards) {
                Vector obsolete = new Vector();
                for (Board board : map.getBoards()) {
                    ASLBoard b = (ASLBoard) board;
                    for (Enumeration e2 = b.getOverlays(); e2.hasMoreElements(); ) {
                        Overlay o = (Overlay) e2.nextElement();
                        if (o.getClass().equals(Overlay.class)) { // Don't check for SSROverlays
                            String name = o.getFile().getName();
                            String availableVersion = overlayVersions.getProperty(name, o.getVersion());
                            if (!availableVersion.equals(o.getVersion())) {
                                obsolete.addElement(name);
                            }
                        }
                    }
                }
                String msg = null;
                if (obsolete.size() == 1) {
                    String name = (String) obsolete.firstElement();
                    msg = "Version " + overlayVersions.getProperty(name) + " of overlay " + name + " is now available.\n" + boardPageURL;
                } else if (obsolete.size() > 1) {
                    StringBuffer buff = new StringBuffer();
                    for (int i = 0, j = obsolete.size(); i < j; ++i) {
                        buff.append((String) obsolete.elementAt(i));
                        if (i < j - 2) {
                            buff.append(", ");
                        } else if (i < j - 1) {
                            buff.append(" and ");
                        }
                    }
                    msg = "New versions of overlays " + buff + " are available.\n" + boardPageURL;
                }
                if (msg != null) {
                    final String message = msg;
                    Runnable runnable = new Runnable() {
                        public void run() {
                            JOptionPane.showMessageDialog(map.getView().getTopLevelAncestor(), message);
                        }
                    };
                    SwingUtilities.invokeLater(runnable);
                }
            }
        }
    }

    private Properties readVersionList(String s) {
        Properties p = null;
        if (s != null
                && s.length() > 0) {
            try {
                p = new PropertiesEncoder(s).getProperties();
            } catch (IOException e) {
                // Fail silently if we can't contact the server
            }
        }
        return p;
    }

    public void propertyChange(PropertyChangeEvent evt) {

        if (Boolean.TRUE.equals(evt.getNewValue()) && !autoSynchBoards) {
            try {
                URL base = new URL(boardVersionURL);
                URLConnection conn = base.openConnection();
                conn.setUseCaches(false);

                Properties p = new Properties();
                InputStream input = null;
                try {
                    input = conn.getInputStream();
                    p.load(input);
                } finally {
                    IOUtils.closeQuietly(input);
                }

                boardVersions = p;
                GameModule.getGameModule().getPrefs().getOption(BOARD_VERSIONS).setValue(new PropertiesEncoder(p).getStringValue());
            } catch (IOException e) {
                // Fail silently if we can't contact the server
            }

            try {
                URL base = new URL(overlayVersionURL);
                URLConnection conn = base.openConnection();
                conn.setUseCaches(false);

                Properties p = new Properties();
                InputStream input = null;
                try {
                    input = conn.getInputStream();
                    p.load(input);
                } finally {
                    IOUtils.closeQuietly(input);
                }

                overlayVersions = p;
                GameModule.getGameModule().getPrefs().getOption(OVERLAY_VERSIONS).setValue(new PropertiesEncoder(p).getStringValue());
            } catch (IOException e) {
                // Fail silently if we can't contact the server
            }
        }
    }
}
