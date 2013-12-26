package org.vasl.tests;

import VASL.Constants;
import VASL.build.module.map.BoardSynchronizer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Tests for the BoardSynchronizer.
 */
public class TestBoardSynchronizer {


    private static final Logger logger = LoggerFactory.getLogger(TestBoardSynchronizer.class);

    @Test
    public void testInitRepo() throws Exception {
        Constants constants = new Constants();
        BoardSynchronizer synchronizer = new BoardSynchronizer();
        logger.info("Default Locale: " + Locale.getDefault());
        logger.info("Root Locale: " + Locale.ENGLISH);
        synchronizer.synchronize(constants.getBoardFolder(), logger, null);
    }
}
