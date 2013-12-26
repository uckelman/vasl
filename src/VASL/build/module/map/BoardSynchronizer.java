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

import VASL.Constants;
import VASSAL.build.Buildable;
import VASSAL.build.Builder;
import VASSAL.build.GameModule;
import VASSAL.build.module.GameComponent;
import VASSAL.command.Command;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.nls.NLS;
import org.slf4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * The BoardSynchronizer synchronizes boards from a remote repository.
 */
public class BoardSynchronizer implements Buildable, GameComponent {

    public void build(Element e) {
        Builder.build(e, this);
    }

    public void add(Buildable child) {
        // we do nothing as this class does not accept children.
    }

    public Element getBuildElement(Document doc) {
        return doc.createElement(getClass().getName());
    }

    /**
     * Will check on GitHub for new board versions and sync them asynchronously.
     */
    public void addTo(Buildable parent) {
        GameModule.getGameModule().getGameState().addGameComponent(this);

        //final File boardFolder = (File) GameModule.getGameModule().getPrefs().getValue("boardURL");

        Runnable runnable = new Runnable() {
            public void run() {
                File boardFolder = Constants.getConstants().getBoardFolder();
                try {
                    synchronize(boardFolder, null, GameModule.getGameModule());
                } catch (Exception e) {
                    GameModule.getGameModule().warn("Problem synchronizing the maps: " + e.getMessage());
                }
            }
        };
        new Thread(runnable).start();
    }

    /**
     * Initialize the boards folder. It must be empty; if not show a message
     *
     * @param boardFolder A file directory that exists.
     */
    public void synchronize(File boardFolder, Logger logger, GameModule module) throws Exception {
        // we need to set the Locale to ROOT lest JGit will fail horribly if his royal properties are
        // not translated to Swahili or whatever.
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);
        NLS.setLocale(Locale.ENGLISH);
        try {
            Git git = Git.open(boardFolder);
            FetchCommand fetch = git.fetch();
            if (logger != null) {
                fetch.setProgressMonitor(new GitProgressMonitor(logger));
            } else if (module != null) {
                fetch.setProgressMonitor(new GitProgressMonitor(module));
            }
            fetch.call();
        } catch (IOException e) {
            // the repository needs to be initialized
            CloneCommand cloneCommand = Git.cloneRepository();
            cloneCommand.setDirectory(boardFolder);
            cloneCommand.setURI(Constants.REMOTE_BOARD_URL);
            if (logger != null) {
                cloneCommand.setProgressMonitor(new GitProgressMonitor(logger));
            } else if (module != null) {
                cloneCommand.setProgressMonitor(new GitProgressMonitor(module));
            }
            try {
                Git repository = cloneCommand.call();
            } catch (GitAPIException e1) {
                throw new RuntimeException("Error while cloning from remote: " + e1.getMessage());
            }
        } catch (InvalidRemoteException e) {
            throw new RuntimeException("Invalid remote: " + e.getMessage());
        } catch (TransportException e) {
            throw new RuntimeException("Transport problem: " + e.getMessage());
        } catch (GitAPIException e) {
            throw new RuntimeException("Git problem: " + e.getMessage());
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    public Command getRestoreCommand() {
        return null;
    }

    public void setup(boolean gameStarting) {
    }

    /**
     * Reports either on the GameModule or a given Logger, the later for tests.
     */
    private class GitProgressMonitor implements ProgressMonitor {

        int tasks = 0;
        int current = 0;
        private Logger logger;
        private GameModule gameModule;

        public GitProgressMonitor(Logger logger) {
            this.logger = logger;
        }

        public GitProgressMonitor(GameModule module) {
            this.gameModule = module;
        }

        public void start(int i) {
        }

        public void beginTask(String s, int i) {
            current = 0;
            tasks = i;
            write(s + ": " + i);
        }

        public void update(int i) {
            current = current + i;
            write("UPDATE " + current + "/" + tasks);
        }

        public void endTask() {
            write("done.");
        }

        public boolean isCancelled() {
            return false;
        }

        private void write(String s) {
            if (logger != null) {
                logger.info(s);
            } else if (gameModule != null) {
                gameModule.warn(s);
            }
        }
    }
}