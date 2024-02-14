/*
 * $Id: ASLMap.java 8530 2012-12-26 04:37:04Z uckelman $
 *
 * Copyright (c) 2013 by Brent Easton
 *
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

package VASL.build.module;

import VASL.LOS.LOSDataEditor;
import VASL.LOS.Map.Hex;
import VASL.LOS.Map.Location;
import VASL.LOS.Map.Terrain;
import VASL.LOS.counters.CounterMetadataFile;
import VASL.build.module.map.boardArchive.SharedBoardMetadata;
import VASL.build.module.map.boardPicker.ASLBoard;
import VASL.build.module.map.boardPicker.BoardException;
import VASL.build.module.map.boardPicker.Overlay;
import VASL.build.module.map.boardPicker.VASLBoard;
import VASSAL.build.GameModule;
import VASSAL.build.module.GameComponent;
import VASSAL.build.module.Map;
import VASSAL.build.module.PieceWindow;
import VASSAL.build.module.map.boardPicker.Board;
import VASSAL.build.widget.ListWidget;
import VASSAL.build.widget.PanelWidget;
import VASSAL.build.widget.PieceSlot;
import VASSAL.configure.ColorConfigurer;
import VASSAL.counters.GamePiece;
import VASSAL.counters.Properties;
import VASSAL.counters.Stack;
import VASSAL.tools.DataArchive;
import VASSAL.tools.ErrorDialog;
import VASSAL.tools.imageop.Op;
import org.jdom2.JDOMException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static VASSAL.build.GameModule.getGameModule;
import static java.lang.Math.cos;

public class ASLMap extends Map {

    // FredKors popup menu
    private JPopupMenu m_mnuMainPopup = null;

    // board metadata
    //TODO: the shared metadata is static so it doesn't have to be passed around. Is there a better way?
    private VASL.LOS.Map.Map VASLMap;
    private static final String sharedBoardMetadataFileName = "boardData/SharedBoardMetadata.xml"; // name of the shared board metadata file
    private static SharedBoardMetadata sharedBoardMetadata = null;
    private boolean legacyMode;                     // true if unable to create a VASL map or LOS data is missing
    // counter metadata
    private static CounterMetadataFile counterMetadata = null;

    // used to log errors in the VASSAL error log
    private static final Logger logger = LoggerFactory.getLogger(ASLMap.class);
    private ShowMapLevel m_showMapLevel = ShowMapLevel.ShowAll;
    // background color preference
    private static final String preferenceTabName = "VASL";
    //JY - independent zoom factors for the boards and pieces
    //Intended to be activated by a separate extension, but needs to be coded in the main module
    private double bZoom; //Additional zoom factor for the boards only
    public double getbZoom() {
        return bZoom;
    }
    public void setbZoom (double z) {
        bZoom = z;
    }
    private ArrayList<String> PieceSlotGpIdList = new ArrayList<>(); //List of pieces that scale with the board (mostly overlays)
    public static final String SCALEWITHBOARDZOOM = "ScaleWithBoardZoom"; //Property name for any counters that should scale with the board zoom level
    public static final String SCALEWITHBOARDMAG = "ScaleWithBoardMag"; //Property name for any counters that should also scale with the board magnification (not the same as the zoom)
    private ArrayList<String> dxAvailBoards = new ArrayList<>(); //List of all available deluxe boards
    public final int baseunexSepX = 2; //default unexpanded and expanded stack separations
    public final int baseunexSepY = 4;
    public final int baseexSepX = 16;
    public final int baseexSepY = 32;
    public int unexSepX; //stack separations, must vary with board zoom to avoid appearance of counter "drift"
    public int unexSepY;
    public int exSepX;
    public int exSepY;
  public ASLMap() {


      super();

      //JY
      setbZoom(1.0D);
      unexSepX = baseunexSepX;
      unexSepY = baseunexSepY;
      exSepX = baseexSepX;
      exSepY = baseexSepY;
      //JY

      try {
          readMetadata();
      } catch (JDOMException e) {

          // give up if there's any problem reading the shared metadata file
          ErrorDialog.bug(e);
      }
      m_mnuMainPopup = new JPopupMenu();

      // FredKors: creation of the toolbar button
      // that opens the popup menu
      JButton l_Menu = new JButton();

    try
    {
        l_Menu.setIcon(new ImageIcon(Op.load("QC/menu.png").getImage(null)));
    }
    catch (Exception ex) 
    {
        ex.printStackTrace();
    }
    
    l_Menu.setMargin(new Insets(0, 0, 0, 0));
    l_Menu.setAlignmentY(0.0F);
    
    l_Menu.addActionListener(new ActionListener() 
    {
        public void actionPerformed(ActionEvent evt) 
        {
            if (evt.getSource() instanceof JButton)
                m_mnuMainPopup.show((JButton)evt.getSource(), 0, 0);
        }
    });

    // add the first element to the popupp menu
    JMenuItem l_SelectItem = new JMenuItem("Select");
    l_SelectItem.setBackground(new Color(255,255,255));
    m_mnuMainPopup.add(l_SelectItem);
    m_mnuMainPopup.addSeparator();    

    // add the menu button to the toolbar
    getToolBar().add(l_Menu); 
    getToolBar().addSeparator();
    // background color preference
    final ColorConfigurer backgroundcolor = new ColorConfigurer("backcolor", "Set Color of space around Map (requires VASL restart)", Color.white);
    getGameModule().getPrefs().addOption(preferenceTabName, backgroundcolor);


}
  
  /*
   *  Work-around for VASL board being 1 pixel too large causing double stacks to form along board edges.
   *  Any snap to a board top or left edge half hex, bump it 1 pixel up or left on to the next board.
   *  
   *  */
  public Point snapTo(Point p) 
  { // FredKors 22/12/2013
    final Point l_pSnapTo = super.snapTo(p);
    Point l_pShiftedXY, l_pShiftedY, l_pShiftedX;
    
    l_pShiftedXY = new Point (l_pSnapTo);

    l_pShiftedXY.x -= 3;
    l_pShiftedXY.y -= 3; // I move the snap point 3 pixel up and left: if the map changes, the snapTo could return a different point, otherwise nothing changes
    l_pShiftedXY = super.snapTo(l_pShiftedXY);
    
    if (findBoard(l_pShiftedXY) != null) //  Return to the snapTo point if I moved off the top border or the left border
        return l_pShiftedXY;
    
    l_pShiftedY = new Point (l_pSnapTo);
    
    l_pShiftedY.y -= 3; // I move the snap point 3 pixel up: if the map changes, the snapTo could return a different point, otherwise nothing changes
    l_pShiftedY = super.snapTo(l_pShiftedY);
    
    if (findBoard(l_pShiftedY) == null) // I moved off the top border, return to the snapTo point
        l_pShiftedY.y = l_pSnapTo.y;
    
    l_pShiftedX = new Point (l_pShiftedY);
    
    l_pShiftedX.x -= 3;// I move the snap point 3 pixel left: if the map changes, the snapTo could return a different point, otherwise nothing changes
    l_pShiftedX = super.snapTo(l_pShiftedX);
    
    if (findBoard(l_pShiftedX) == null) // I moved off the left border
        return l_pShiftedY;
    
    return l_pShiftedX;        
    
  }
  // return the popup menu
  public JPopupMenu getPopupMenu()
  {
      return m_mnuMainPopup;
  }

    @Override
    public synchronized void setBoards(Collection<Board> c) {

        for (Board boardc: c) {
            VASLBoard testboardexists = (VASLBoard) boardc;
            if (testboardexists.getVASLBoardArchive() == null) {
                GameModule.getGameModule().getChatter().send("Board missing. Auto-synching of boards requires board directory in board picker matches the board directory set in preferences. Close this game and start new game");
                return;
            }
        }
        super.setBoards(c);
        String info = "Using board(s): ";
        for (Board board : boards) {
            ASLBoard b = (ASLBoard) board;
            info += b.getName() + "(v" + b.getVersion() + ") ";
        }
        GameModule.getGameModule().warn(info);
        buildVASLMap();

        //JY
        findOverlays();
        createDeluxeBoardsList();
        //JY

        // Add OBObserver location
        if (VASLMap!=null){
            for (GameComponent gc: GameModule.getGameModule().getGameState().getGameComponents()){
                String classname = gc.getClass().getName();
                if (gc.getClass().getName() =="VASL.build.module.OBA" ){
                    OBA oba = (OBA) gc;
                    oba.checkforOBO();
                }
            }
        }

    }

    /**
     * read the shared board metadata
     */
    private void readMetadata() throws JDOMException {

        DataArchive archive = GameModule.getGameModule().getDataArchive();
        // shared board metadata
        try (InputStream inputStream =  archive.getInputStream(sharedBoardMetadataFileName)) {
            sharedBoardMetadata = new SharedBoardMetadata();
            sharedBoardMetadata.parseSharedBoardMetadataFile(inputStream);

        // give up on any errors
        } catch (IOException e) {
            sharedBoardMetadata = null;
            throw new JDOMException("Cannot read the shared metadata file", e);
        } catch (JDOMException e) {
            sharedBoardMetadata = null;
            throw new JDOMException("Cannot read the shared metadata file", e);
        } catch (NullPointerException e) {
            sharedBoardMetadata = null;
            throw new JDOMException("Cannot read the shared metadata file", e);
        }
    }

    /**
     * Builds the VASL map
     */
    protected void buildVASLMap() {
        // set background color from preference
        super.bgColor = (Color) getGameModule().getPrefs().getValue("backcolor");
        Boolean alwaysontop = Boolean.TRUE.equals(getGameModule().getPrefs().getValue("PWAlwaysOnTop"));
        getGameModule().getPlayerWindow().setAlwaysOnTop(alwaysontop);
        repaint();
        legacyMode = false;
        boolean nullBoards = false; // are null boards being used?

        // create an empty VASL map of the correct size
        LinkedList<VASLBoard> VASLBoards = new LinkedList<VASLBoard>(); // keep the boards so they are instantiated once
        try {

            // see if there are any legacy boards in the board set
            // and determine the size of the map
            final Rectangle mapBoundary = new Rectangle(0,0);
            double hexHeight = 0.0;
            double hexWidth = 0.0;
            for(Board b: boards) {

                final VASLBoard board = (VASLBoard) b;

                // ignore null boards
                if(!"NUL".equals(b.getName()) && !"NULV".equals(b.getName())){

                    if(board.isLegacyBoard()) {
                        throw new BoardException("VASL LOS disabled - Board " + board.getName() + " does not support LOS checking. VASSAL los active");
                    }

                    mapBoundary.add(b.bounds());
                    VASLBoards.add(board);

                    // make sure the hex geometry of all boards is the same
                    if (hexHeight != 0.0 && Math.round(board.getHexHeight()) != Math.round(hexHeight) || hexWidth != 0.0 && Math.round(board.getHexWidth()) != Math.round(hexWidth)) {
                        throw new BoardException("VASL LOS disabled: Map configuration contains multiple hex sizes. VASSAL los active");
                    }
                    hexHeight = board.getHexHeight();
                    hexWidth = board.getHexWidth();
                }
                else {
                    nullBoards = true;
                }
            }
            // this is a hack to fix problem with board geometry. Standard geo hexes cannot have a width greater than 56.25 or they will exceed the board size of 1800 pixels
            // even if they are 56.3125 in size
            // need to edit BoardMetaData.xml to change hexHeight to 56.25
            if (hexWidth == 56.3125){hexWidth = 56.25;}


            // remove the edge buffer from the map boundary size
            mapBoundary.width -= edgeBuffer.width;
            mapBoundary.height -= edgeBuffer.height;

            // create the VASL map
            //DR added new variables to pass cropping values
            String passgridconfig="Normal";
            boolean isCropping=false;
            int fullhexadj=0;

            VASLBoard b = VASLBoards.get(0); // we can use the geometry of any board - assuming all are the same
            if (b.getVASLBoardArchive().getHexGridConfig() != null) {passgridconfig = b.getVASLBoardArchive().getHexGridConfig();}
            if (b.isCropped()) { isCropping=true;}
            if (b.nearestFullRow) {
                passgridconfig = "FullHex";
                fullhexadj=-1;
                if (b.getCropBounds().getX() == 0) {passgridconfig = "FullHexLeftHalf"; fullhexadj=0;}
                if (b.getCropBounds().getMaxX() == b.getUncroppedSize().getWidth()) {passgridconfig = "FullHexRightHalf"; fullhexadj=0;}
            }
            //if (!(b.getA1CenterX()==0) && !(b.getA1CenterX()==-999)) { passgridconfig= passgridconfig +"Offset";}
            double passA1CenterY = b.getA1CenterY();
            if (!(b.getA1CenterX()==0) && !(b.getA1CenterX()==-999) && !(b.getA1CenterX()==-901)) {
                if (b.getCropBounds().getX() != 0) {
                    passgridconfig = passgridconfig + "Offset";  // only need to set this if cropping the left edge when board has offset (ie RB and RO)
                }
                //if(b.getA1CenterY()==65 && b.getCropBounds().getX()==0){passA1CenterY =32.25;}
            }
            VASLMap = new VASL.LOS.Map.Map(
                    hexWidth,
                    hexHeight,
                    (int) Math.round(mapBoundary.width/ b.getHexWidth()) + 1 + fullhexadj,
                    (int) Math.round(mapBoundary.height/ b.getHexHeight()),
                    b.getA1CenterX(),
                    passA1CenterY,
                    mapBoundary.width,
                    mapBoundary.height,
                    sharedBoardMetadata.getTerrainTypes(), passgridconfig, isCropping);
        }

        // clean up and fall back to legacy mode if an unexpected exception is thrown
        catch (BoardException e) {

            setLegacyMode();
            logError(e.toString());
            GameModule.getGameModule().getChatter().send(e.toString());
        }
        catch (Exception e) {

            setLegacyMode();
            VASLBoards = null;
            logError("LOS disabled - unexpected error");
            logException(e);
            GameModule.getGameModule().getChatter().send("LOS disabled - unexpected error: " + e.toString());
        }

        // add the boards to the VASL map
        try {

            // load the LOS data
            if(!legacyMode) {

                for (VASLBoard board : VASLBoards) {

                    // read the LOS data and flip/crop the board if needed
                    // DR added variable to support cropping and flipping
                    String croptype="Normal"; boolean isCropping=false; double Fullhexadj= 0;
                    double gridadj=0;

                    if (board.nearestFullRow) {
                        croptype="FullHex";
                        Fullhexadj= board.getHexWidth()/2;
                        if (board.getCropBounds().getX()==0) {croptype="FullHexLeftHalf";}
                        if (board.getCropBounds().getMaxX()== board.getUncroppedSize().getWidth()) {croptype="FullHexRightHalf";}
                    }
                    if(board.isCropped()) {
                        isCropping=true;
                        if (!croptype.contains("LeftHalf")) {
                            if (!(board.getA1CenterX() == -901)) {
                                gridadj = board.getA1CenterX() - Fullhexadj;
                                if (board.getCropBounds().width== -1) {gridadj=0;}
                            } else {
                                gridadj = -Fullhexadj;
                            }
                        }

                    }
                    if (!(board.getA1CenterX()==0) && !(board.getA1CenterX()==-999) && !(board.getA1CenterX()==-901)) {
                        if (board.getCropBounds().getX() != 0) {
                            croptype = croptype + "Offset";  // only need to set this if cropping the left edge when board has offset (ie RB and RO)
                        }
                    }
                    VASL.LOS.Map.Map LOSData = board.getLOSData(sharedBoardMetadata.getTerrainTypes(), croptype, isCropping, gridadj);

                    // apply the SSR changes, crop and flip if needed
                    board.applyColorSSRules(LOSData, sharedBoardMetadata.getLOSSSRules(), gridadj);

                    if(board.isCropped()) {
                        LOSData = board.cropLOSData(LOSData);
                    }

                    if(board.isReversed()){
                        LOSData.flip();
                    }
                    //new code for adding overlays to LOS
                    LOSData = adjustLOSForOverlays(board, LOSData);
                    // add the board LOS data to the map
                    // .insertMap is designed to work with only geo board thus need to test for non-geo boards (in this situation geo boards inclues AP boards and deluxe boards)
                    if ((board.getWidth()==33 && board.getHeight()==10) || (board.getWidth()==17 && board.getHeight()==20) || (board.getWidth() == 15 && board.getHeight() ==5)) {
                        //line below is not a good fix; make sure it works in all situations or change
                            int cropadj=1;  // ensures that cropping a board by row works properly DR (rows such as A7 have uneven total height which results in incorrect choice from gridToHex)
                            if (!VASLMap.insertMap(
                                    LOSData,
                                    VASLMap.gridToHex(board.getBoardLocation().x, board.getBoardLocation().y + cropadj + (nullBoards ? 1 : 0)))) {

                                // didn't work, so assume an unsupported feature
                                throw new BoardException("VASL LOS Disabled: Unable to insert board " + board.getName() + " into the VASL map. VASSAL los active");
                            }
                    }
                    else {
                            // add board LOS data for non-standard size board
                        //line below is not a good fix; make sure it works in all situations or change
                        int cropadj=1;  // ensures that cropping a board by row number works properly DR (rows such as A7 have uneven total height which results in incorrect choice from gridToHex)
                        if (VASLBoards.size()==1){
                            VASLMap.insertOneMap(LOSData);
                        } else {
                            if (!VASLMap.insertNonGeoMap(
                                    LOSData,
                                    VASLMap.gridToHex(board.getBoardLocation().x, board.getBoardLocation().y + cropadj + (nullBoards ? 1 : 0)))) {

                                // didn't work, so assume an unsupported feature
                                throw new BoardException("VASL LOS Disabled: Unable to insert board " + board.getName() + " into the VASL map. VASSAL los active");
                            }
                        }
                    }
                }
                GameModule.getGameModule().warn("VASL LOS Enabled");
            }
        }
        catch (BoardException e) {

            setLegacyMode();
            logError(e.toString());
            GameModule.getGameModule().getChatter().send("VASL LOS Disabled: " + e.toString() + ". VASSAL los active");
        }
        catch (Exception e) {

            setLegacyMode();
            logError("LOS disabled - unexpected error");
            logException(e);
            GameModule.getGameModule().getChatter().send("VASL LOS disabled: " + e.toString() + ". VASSAL los active");
        }
        finally {
            // free up memory
            VASLBoards = null;
        }
    }
    /**
     * A class that allows the LOSData, Graphic image and point information to be passed to various methods and classes
     * Note that all properties are public to eliminate getter/setter clutter
     */
    private class LOSonOverlays {
        public VASL.LOS.Map.Map newlosdata;
        public BufferedImage bi;
        public VASLBoard board;
        public Rectangle ovrRec;
        public int currentx;
        public int currenty;
        public int overpositionx;
        public int overpositiony;

    }
    private VASL.LOS.Map.Map adjustLOSForOverlays(VASLBoard board, VASL.LOS.Map.Map losdata){
        LOSonOverlays losonoverlays = new LOSonOverlays();
        losonoverlays.newlosdata = losdata;
        losonoverlays.board = board;
        final Enumeration overlays = board.getOverlays();
        while (overlays.hasMoreElements()) {
            Overlay o = (Overlay) overlays.nextElement();
            if(o.getName().equals("")){continue;} // prevents error when using underlays (which are added as overlays)
            if(o.getName().contains("BSO") && (!o.getName().contains("BSO_LFT3"))){continue;} // prevents error when using BSO which are handled elsewhere
            // BSO_LFT3 may be a special case; treat it as so for now; if find others then need to develop a proper solution
            losonoverlays.ovrRec = o.bounds();
            // get the image as a buffered image
            Image i = o.getImage();
            losonoverlays.bi = new BufferedImage(i.getWidth(null), i.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            Graphics2D bGr = losonoverlays.bi.createGraphics();
            bGr.drawImage(i, 0, 0, null);
            bGr.dispose();
            if (o.getFile().getName().equalsIgnoreCase("ovrH")) {
                setHillockTerrain(losonoverlays);
            } else if (o.getFile().getName().equalsIgnoreCase("ovrD")) {
                setDierTerrain(losonoverlays);
                setDierLip(losonoverlays);
            } else if (o.getFile().getName().equalsIgnoreCase("ovrSD")) {
                setSandDuneTerrain(losonoverlays);
            } else if (o.getFile().getName().equalsIgnoreCase("ovrW")) {
                setWadiTerrain(losonoverlays);
            } else {
                String terraintype = getoverlayterraintype(o);
                setOverlayTerrain(losonoverlays, terraintype, o.getpreserveelevation());
            }

        }

       return losonoverlays.newlosdata;

    }

    private void setHillockTerrain(LOSonOverlays losonoverlays){
        if (losonoverlays.board.isReversed()){
            // flip the overlay grid
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Hillock";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            Color testcolor = new Color(114, 83, 42); //have to use method as several colors have same RGB values
                            if (color.equals(testcolor)) {
                                terraintouse = "Hillock Summit";
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1);
                            if (!(losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1).getCenterLocation().getTerrain().getName().equals("Hillock Summit"))) {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terraintouse));
                            } else {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1).getCenterLocation().setBaseHeight(1);
                            }
                        }
                    }
                }
            }
        } else {
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Hillock";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            Color testcolor = new Color(114, 83, 42); //have to use method as several colors have same RGB values
                            if (color.equals(testcolor)) {
                                terraintouse = "Hillock Summit";
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y);
                            if (!(losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().getTerrain().getName().equals("Hillock Summit"))) {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terraintouse));
                            } else {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().setBaseHeight(1);
                            }
                        }
                    }
                }
            }
        }

    }
    private void setDierTerrain(LOSonOverlays losonoverlays){
        if (losonoverlays.board.isReversed()){
            // flip the overlay grid
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Dier";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                            if (terrint >= 0) {
                                terr = losonoverlays.newlosdata.getTerrain(terrint);
                                if (terr.getName().contains("Scrub")) {
                                    terraintouse = "Scrub";
                                } else if (terr.getName().equals("Dier")) {
                                    terraintouse = "Dier";
                                }
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1);
                            if (!(losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1).getCenterLocation().getTerrain().getName().equals("Scrub"))) {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terraintouse));
                            }
                        }
                    }
                }
            }
        } else {

            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Dier";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                            if (terrint >= 0) {
                                terr = losonoverlays.newlosdata.getTerrain(terrint);
                                if (terr.getName().contains("Scrub")) {
                                    terraintouse = "Scrub";
                                } else if (terr.getName().equals("Dier")) {
                                    terraintouse = "Dier";
                                }
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y);
                            if (!(losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().getTerrain().getName().equals("Scrub"))) {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terraintouse));
                            }
                        }
                    }
                }
            }
        }

    }
    private void setDierLip(LOSonOverlays losonoverlays){
        // step through each hex and reset the terrain.
        if(losonoverlays.newlosdata.getMapConfiguration().equals("TopLeftHalfHeightEqualRowCount") || losonoverlays.newlosdata.getA1CenterY()==65){
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.newlosdata.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.newlosdata.getHeight(); losonoverlays.currenty++) { // no extra hex for boards where each col has same number of rows (eg RO)
                    if(losonoverlays.newlosdata.getHex(losonoverlays.currentx, losonoverlays.currenty).getCenterLocation().getTerrain().getName().equals("Dier")){
                        for (int a =0; a <6; a++) {
                            Hex testhex = losonoverlays.newlosdata.getAdjacentHex(losonoverlays.newlosdata.getHex(losonoverlays.currentx, losonoverlays.currenty), a);
                            if ((testhex==null) || !(testhex.getCenterLocation().getTerrain().getName().equals("Dier"))) {
                                losonoverlays.newlosdata.getHex(losonoverlays.currentx, losonoverlays.currenty).setHexsideTerrain(a, losonoverlays.newlosdata.getTerrain("Dier Lip"));
                                losonoverlays.newlosdata.getHex(losonoverlays.currentx,losonoverlays.currenty).setHexsideLocationTerrain(a, losonoverlays.newlosdata.getTerrain("Dier Lip"));
                            }
                        }
                    }
                }
            }
        } else {
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.newlosdata.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.newlosdata.getHeight() + (losonoverlays.currentx % 2); losonoverlays.currenty++) { // add 1 hex if odd
                    if(losonoverlays.newlosdata.getHex(losonoverlays.currentx, losonoverlays.currenty).getCenterLocation().getTerrain().getName().equals("Dier")){
                        for (int a =0; a <6; a++) {
                            Hex testhex = losonoverlays.newlosdata.getAdjacentHex(losonoverlays.newlosdata.getHex(losonoverlays.currentx, losonoverlays.currenty), a);
                            if ((testhex==null) || !(testhex.getCenterLocation().getTerrain().getName().equals("Dier"))) {
                                losonoverlays.newlosdata.getHex(losonoverlays.currentx, losonoverlays.currenty).setHexsideTerrain(a, losonoverlays.newlosdata.getTerrain("Dier Lip"));
                                losonoverlays.newlosdata.getHex(losonoverlays.currentx,losonoverlays.currenty).setHexsideLocationTerrain(a, losonoverlays.newlosdata.getTerrain("Dier Lip"));
                            }
                        }
                    }
                }
            }
        }
    }

    private void setSandDuneTerrain(LOSonOverlays losonoverlays){
        if (losonoverlays.board.isReversed()){
            // flip the overlay grid
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Sand Dune, Low";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                            if (terrint >= 0) {
                                terr = losonoverlays.newlosdata.getTerrain(terrint);
                                if (terr.getName().equals("Dune, Crest Low")) {
                                    terraintouse = "Dune, Crest Low";
                                } else if (terr.getName().contains("Scrub")) {
                                    terraintouse = "Scrub";
                                }
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1);
                            if (terraintouse.equals("Dune, Crest Low")) {
                                setDuneCrest(losonoverlays,losonoverlays.newlosdata.getGridWidth() - -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() -losonoverlays.currenty -1, losonoverlays.ovrRec, true);
                            }
                            losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain("Sand Dune, Low"));

                        }
                    }
                }
            }
        } else {
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Sand Dune, Low";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                            if (terrint >= 0) {
                                terr = losonoverlays.newlosdata.getTerrain(terrint);
                                if (terr.getName().equals("Dune, Crest Low")) {
                                    terraintouse = "Dune, Crest Low";
                                } else if (terr.getName().contains("Scrub")) {
                                    terraintouse = "Scrub";
                                }
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y);
                            if (terraintouse.equals("Dune, Crest Low")) {
                                setDuneCrest(losonoverlays, losonoverlays.currentx, losonoverlays.currenty, losonoverlays.ovrRec, false);
                            }
                            losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain("Sand Dune, Low"));
                        }
                    }
                }
            }
        }

    }
    private void setDuneCrest(LOSonOverlays losonoverlays, int usepositionx, int usepositiony, Rectangle ovrRec, boolean isreversed){
        // reset the terrain
        Hex dunehex = null;
        Location dunecrestloc=null;
        if(isreversed){
            dunehex = losonoverlays.newlosdata.gridToHex(usepositionx - ovrRec.x, usepositiony - ovrRec.y);
            dunecrestloc = dunehex.getNearestLocation(usepositionx - ovrRec.x, usepositiony);
            dunehex = losonoverlays.newlosdata.gridToHex(usepositionx + ovrRec.x, usepositiony + ovrRec.y);
            dunecrestloc = dunehex.getNearestLocation(usepositionx + ovrRec.x, usepositiony);
        }
        int hexside = dunehex.getLocationHexside(dunecrestloc);
        if (hexside != -1) {
            dunehex.setHexsideTerrain(hexside, losonoverlays.newlosdata.getTerrain("Dune, Crest Low"));
            dunehex.setHexsideLocationTerrain(hexside, losonoverlays.newlosdata.getTerrain("Dune, Crest Low"));
        }
    }
    private void setWadiTerrain(LOSonOverlays losonoverlays){
        if (losonoverlays.board.isReversed()){
            // flip the overlay grid
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Open Ground";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                            if (terrint >= 0) {
                                terr = losonoverlays.newlosdata.getTerrain(terrint);
                                if (terr.getName().equals("Wadi")) {
                                    terraintouse = "Wadi";
                                } else if (terr.getName().equals("Cliff")) {
                                    terraintouse = "Cliff";
                                }
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1);
                            if (terraintouse == "Wadi") {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x - losonoverlays.currentx - 1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y - losonoverlays.currenty - 1).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain("Wadi"));
                                losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x - losonoverlays.currentx - 1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y - losonoverlays.currenty - 1).getCenterLocation().setBaseHeight(-1);
                            }
                        }
                    }
                }
            }
        } else {

            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y)) {
                        int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                        if ((c >> 24) != 0x00) { // not a transparent pixel
                            String terraintouse = "Open Ground";
                            Terrain terr;
                            //Retrieving the R G B values
                            Color color = getRGBColor(c);
                            int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                            if (terrint >= 0) {
                                terr = losonoverlays.newlosdata.getTerrain(terrint);
                                if (terr.getName().equals("Wadi")) {
                                    terraintouse = "Wadi";
                                } else if (terr.getName().equals("Cliff")) {
                                    terraintouse = "Cliff";
                                }
                            }
                            losonoverlays.newlosdata.setGridTerrainCode(losonoverlays.newlosdata.getTerrain(terraintouse).getType(), losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y);
                            if (terraintouse == "Wadi") {
                                losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain("Wadi"));
                                losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y).getCenterLocation().setBaseHeight(-1);
                                // need to set depression and cliff hexsides, but how?
                            }
                        }
                    }
                }
            }
        }

    }

    // this is the generic method for terrain overlays
    private void setOverlayTerrain(LOSonOverlays losonoverlays, String terraintype, boolean preserveelevation){
        // first test for inherent terrain type and send to separate method; use this method for non-inherent or mixed non-inherent/inherent overlays
        if (isInherenttype(terraintype)) {
            setOverlayInherentTerrain(losonoverlays, terraintype);
        } else {
            HashMap<VASL.LOS.Map.Hex, VASL.LOS.Map.Terrain>  inhHexes = new HashMap<VASL.LOS.Map.Hex, VASL.LOS.Map.Terrain>();
            HashMap<VASL.LOS.Map.Hex, VASL.LOS.Map.Terrain>  bdgHexes = new HashMap<VASL.LOS.Map.Hex, VASL.LOS.Map.Terrain>();
            losonoverlays.overpositionx =0; losonoverlays.overpositiony=0;
            if (losonoverlays.board.isReversed()){
                // flip the overlay grid
                for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                    for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                        int cropheight = losonoverlays.board.getCropBounds().getHeight() == -1 ? (int) losonoverlays.board.getUncroppedSize().getHeight() : (int)losonoverlays.board.getCropBounds().getHeight();
                        int cropwidth = losonoverlays.board.getCropBounds().getWidth() ==-1 ? (int) losonoverlays.board.getUncroppedSize().getWidth() : (int) losonoverlays.board.getCropBounds().getWidth() ;
                        losonoverlays.overpositionx = cropwidth - losonoverlays.ovrRec.x  -losonoverlays.currentx -1 + (int) losonoverlays.board.getCropBounds().getX();
                        losonoverlays.overpositiony = cropheight - losonoverlays.ovrRec.y -losonoverlays.currenty -1 + (int) losonoverlays.board.getCropBounds().getY();
                        if (losonoverlays.newlosdata.onMap(losonoverlays.overpositionx, losonoverlays.overpositiony ) && losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony) !=null) {
                            int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                            Terrain terr= null; int elevint=0;
                            if ((c >> 24) != 0x00) { // not a transparent pixel
                                //Retrieving the R G B values
                                Color color = getRGBColor(c);
                                terr = getTerrainfromColor(color, losonoverlays);
                                int bumpx =0; int bumpy=0;
                                while(terr==null){
                                    color = getnearestcolor(losonoverlays, losonoverlays.overpositionx + bumpx, losonoverlays.overpositiony + bumpy);
                                    if (color.equals(Color.white)){
                                        terr = losonoverlays.newlosdata.getTerrain(losonoverlays.board.getVASLBoardArchive().getTerrainForVASLColor("L0Winter"));
                                    } else {
                                        terr = getTerrainfromColor(color, losonoverlays);
                                        if (terr ==null){bumpx+=1;bumpy+=1;}
                                    }
                                }
                                elevint = getElevationfromColor(losonoverlays, color);
                                if (terr.isDepression()){
                                    elevint = losonoverlays.newlosdata.getGridElevation(losonoverlays.overpositionx, losonoverlays.overpositiony) -1;
                                }
                                //add Hex to collections of inherent hexes and building hexes on the overlay
                                addHextoInhandBldgMaps(terr, losonoverlays, inhHexes, bdgHexes);
                                //set terrain type for center location or hexside location (if hexside terrain)
                                setterraintype (losonoverlays, terr, terraintype);
                                //set elevation level for point and hex
                                if (!preserveelevation) {
                                    // turn this into a method if can do so with reversed board
                                    //set elevation for point
                                    losonoverlays.newlosdata.setGridElevation(elevint, losonoverlays.overpositionx, losonoverlays.overpositiony );
                                    int testx =0; int testy =0;
                                    //adjust point values to match hexgrid data
                                    if ((int)losonoverlays.board.getCropBounds().getX() ==0 && (int)losonoverlays.board.getCropBounds().getWidth() != -1) {
                                        testx = ((int) losonoverlays.board.getUncroppedSize().getWidth()) - ((int) losonoverlays.board.getCropBounds().getWidth() - losonoverlays.overpositionx);
                                    } else {
                                        testx = losonoverlays.overpositionx;
                                    }
                                    if ((int)losonoverlays.board.getCropBounds().getY() ==0 && (int)losonoverlays.board.getCropBounds().getHeight()!=-1) {
                                        testy = (int) losonoverlays.board.getUncroppedSize().getHeight() - ((int) losonoverlays.board.getCropBounds().getHeight() - losonoverlays.overpositiony);
                                    } else {
                                        testy = losonoverlays.overpositiony;
                                    }
                                    //test if pixel is hex center
                                    if (testx  == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getCenterLocation().getLOSPoint()).getX() &&
                                        testy == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getCenterLocation().getLOSPoint()).getY()) {
                                        // if white center dot on overlay aligns with hex center, won't set elevation properly so need to look for nearby terrain type
                                        // bit of a hack but should work - try it until we get a bug
                                        color = getRGBColor(c);
                                        //int j =0; int k = 0;
                                        if (color.equals(Color.white) || color.equals(Color.black)){ // && j<=(x+6)) {
                                        //j += 2;        //k += 2;
                                            color = getnearestcolor(losonoverlays, losonoverlays.overpositionx, losonoverlays.overpositiony);
                                            //int terrint = board.getVASLBoardArchive().getTerrainForColor(color);
                                            if ((color.equals(Color.white))){
                                               elevint =0;
                                            } else {
                                               elevint = losonoverlays.board.getVASLBoardArchive().getElevationForColor(color);
                                            }
                                        }
                                    }
                                    // this sets base elevation for the hex - crest line & depression hexes can contain multiple elevations
                                    // hack for LFT3; change if applies to other boards
                                    if (!losonoverlays.board.getVASLBoardArchive().getVASLColorName(color).contains("SnowHexDots2")) {
                                        losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).setBaseHeight(elevint);
                                    }
                                }
                            } else {
                                // transparent pixel - check if center dot
                                //adjust point values to match hexgrid data
                                int testx =0; int testy =0;
                                if ((int)losonoverlays.board.getCropBounds().getX() ==0 && (int)losonoverlays.board.getCropBounds().getWidth() != -1) {
                                    testx = ((int) losonoverlays.board.getUncroppedSize().getWidth()) - ((int) losonoverlays.board.getCropBounds().getWidth() - losonoverlays.overpositionx);
                                } else{
                                    testx = losonoverlays.overpositionx;
                                }
                                if ((int)losonoverlays.board.getCropBounds().getY() ==0 && (int)losonoverlays.board.getCropBounds().getHeight()!=-1) {
                                    testy = (int) losonoverlays.board.getUncroppedSize().getHeight() - ((int) losonoverlays.board.getCropBounds().getHeight() - losonoverlays.overpositiony);
                                } else {
                                    testy = losonoverlays.overpositiony;
                                }
                                //test if pixel is hex center
                                if (losonoverlays.overpositionx + (int) losonoverlays.board.getCropBounds().getX() == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony ).getHexCenter()).getX() &&
                                        losonoverlays.overpositiony + (int) losonoverlays.board.getCropBounds().getY()  == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx , losonoverlays.overpositiony).getHexCenter()).getY()) {
                                    // if center dot on overlay is transparent and aligns with hex center, won't set elevation properly so need to look for nearby terrain type
                                    // bit of a hack but should work - try it until we get a bug
                                    // the bug is with overlays where the border is transparent so test
                                    // (1) if pixel is on the overlay edge and (2) if so are pixels 2 away also transparent
                                    // in those conditions, skip actions
                                    if (!pixelontransparentoverlayborder(losonoverlays)) {

                                        //test if pixel is hex center
                                        //if (testx  == (int)(newlosdata.gridToHex(ovrx, ovry).getCenterLocation().getLOSPoint()).getX() &&
                                        //        testy == (int)(newlosdata.gridToHex(ovrx, ovry).getCenterLocation().getLOSPoint()).getY()) {

                                        int j = 0;
                                        int k = 0;
                                        elevint = -99;

                                        while ((c >> 24) == 0x00 && j <= 6) {
                                            j += 2;
                                            k += 2;
                                            if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + j, losonoverlays.currenty + k) && pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx+j, losonoverlays.currenty+k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx + j, losonoverlays.currenty + k);
                                            } else if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + j, losonoverlays.currenty - k) && pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx+j, losonoverlays.currenty-k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx + j, losonoverlays.currenty - k);
                                            } else if (losonoverlays.newlosdata.onMap(losonoverlays.currentx - j, losonoverlays.currenty + k) && pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx-j, losonoverlays.currenty+k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx - j, losonoverlays.currenty + k);
                                            } else if (losonoverlays.newlosdata.onMap(losonoverlays.currentx - j, losonoverlays.currenty - k) && pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx-j, losonoverlays.currenty-k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx - j, losonoverlays.currenty - k);
                                            } else {
                                                break;
                                            }
                                            Color color = getRGBColor(c);
                                            //int terrint = board.getVASLBoardArchive().getTerrainForColor(color);
                                            if ((color.equals(Color.white))) {
                                                elevint = 0;
                                            } else {
                                                elevint = losonoverlays.board.getVASLBoardArchive().getElevationForColor(color);
                                            }
                                            //if (terrint >= 0) {
                                            //    terr = newlosdata.getTerrain(terrint);
                                            //}
                                        }
                                        // this sets base elevation for the hex - crest line & depression hexes can contain multiple elevations
                                        if (elevint != -99) {
                                            losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).setBaseHeight(elevint);
                                        }

                                        /*while ((c >> 24) == 0x00 && j <= (x + 6)) {
                                            j += 2;
                                            k += 2;
                                            if (newlosdata.onMap(x + j, y + k)) {
                                                c = bi.getRGB(x + j, y + k);
                                            } else if (newlosdata.onMap(x + j, y - k)) {
                                                c = bi.getRGB(x + j, y - k);
                                            } else if (newlosdata.onMap(x - j, y + k)) {
                                                c = bi.getRGB(x - j, y + k);
                                            } else if (newlosdata.onMap(x - j, y - k)) {
                                                c = bi.getRGB(x - j, y - k);
                                            } else {
                                                break;
                                            }
                                            Color color = getRGBColor(c);
                                            //int terrint = board.getVASLBoardArchive().getTerrainForColor(color);
                                            if ((color.equals(Color.white))) {
                                                elevint = 0;
                                            } else {
                                                elevint = board.getVASLBoardArchive().getElevationForColor(color);
                                            }
                                            //if (terrint >= 0) {
                                            //    terr = newlosdata.getTerrain(terrint);
                                            //}
                                        }
                                        // this sets base elevation for the hex - crest line & depression hexes can contain multiple elevations
                                        if (elevint != -99) {
                                            newlosdata.gridToHex(ovrx, ovry).setBaseHeight(elevint);
                                        }*/
                                    }
                                }
                            }
                        }
                    }
                }
                addinhterraintolos (inhHexes, losonoverlays, losonoverlays.board);
                addbldglevelstolos(bdgHexes, losonoverlays);
            } else {
                for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                    for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                        losonoverlays.overpositionx = losonoverlays.currentx + (int) losonoverlays.ovrRec.getX() - (int) losonoverlays.board.getCropBounds().getX();
                        losonoverlays.overpositiony = losonoverlays.currenty + (int) losonoverlays.ovrRec.getY() - (int) losonoverlays.board.getCropBounds().getY();
                        if (losonoverlays.newlosdata.onMap(losonoverlays.overpositionx, losonoverlays.overpositiony) && losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony) !=null) {
                            int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                            Terrain terr= null; int elevint=0;
                            if ((c >> 24) != 0x00) { // not a transparent pixel
                                //Retrieving the R G B values
                                Color color = getRGBColor(c);
                                terr = getTerrainfromColor(color, losonoverlays);
                               // debug code - delete when finished adjusting code
                               if (terr != null) {
                                   if (!color.equals(Color.BLACK) && !terr.getName().equals("Open Ground")) {
                                       boolean reg = true;
                                   }
                               }
                                int bumpx =0; int bumpy=0;
                                while(terr==null){
                                    color = getnearestcolor(losonoverlays, losonoverlays.overpositionx + bumpx, losonoverlays.overpositiony + bumpy);
                                    if (color.equals(Color.white)){
                                        terr = losonoverlays.newlosdata.getTerrain(losonoverlays.board.getVASLBoardArchive().getTerrainForVASLColor("L0Winter"));
                                    } else {
                                        terr = getTerrainfromColor(color, losonoverlays);
                                        if (terr ==null){bumpx+=1;bumpy+=1;}
                                    }
                                }
                                elevint = getElevationfromColor(losonoverlays, color);
                                if (terr.isDepression()){
                                    elevint = losonoverlays.newlosdata.getGridElevation(losonoverlays.overpositionx, losonoverlays.overpositiony) -1;
                                }
                                //add Hex to collections of inherent hexes and building hexes on the overlay
                                addHextoInhandBldgMaps(terr, losonoverlays, inhHexes, bdgHexes);
                                //set terrain type for center location or hexside location (if hexside terrain)
                                setterraintype (losonoverlays, terr, terraintype);

                                if (!preserveelevation) {
                                    // turn this into a method if can do so with reversed board
                                    //set elevation for point
                                    losonoverlays.newlosdata.setGridElevation(elevint, losonoverlays.overpositionx, losonoverlays.overpositiony );
                                    //test if pixel is hex center
                                    if (losonoverlays.overpositionx + (int) losonoverlays.board.getCropBounds().getX() == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony ).getHexCenter()).getX() &&
                                    losonoverlays.overpositiony + (int) losonoverlays.board.getCropBounds().getY()  == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx , losonoverlays.overpositiony).getHexCenter()).getY()) {
                                        // if white center dot on overlay aligns with hex center, won't set elevation properly so need to look for nearby terrain type
                                        // bit of a hack but should work - try it until we get a bug
                                        color = getRGBColor(c);
                                        //int j =0; int k = 0;
                                        if (color.equals(Color.white) || color.equals(Color.black)){ // && j<=(x+6)) {
                                            //j += 2;
                                            //k += 2;
                                            color = getnearestcolor(losonoverlays, losonoverlays.overpositionx, losonoverlays.overpositiony);
                                            //int terrint = board.getVASLBoardArchive().getTerrainForColor(color);
                                            if ((color.equals(Color.white))){
                                                elevint =0;
                                            } else {
                                                elevint = getElevationfromColor(losonoverlays, color);     //losonoverlays.board.getVASLBoardArchive().getElevationForColor(color);
                                            }
                                            //if (terrint >= 0) {
                                            //    terr = newlosdata.getTerrain(terrint);
                                            //}
                                        }
                                        // this sets base elevation for the hex - crest line & depression hexes can contain multiple elevations
                                        // hack for LFT3; change if applies to other boards
                                        if (!losonoverlays.board.getVASLBoardArchive().getVASLColorName(color).contains("SnowHexDots2")) {
                                            losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).setBaseHeight(elevint);
                                        }

                                    }
                                }
                            } else { // transparent pixel
                                //test if pixel is hex center
                                if (losonoverlays.overpositionx + (int) losonoverlays.board.getCropBounds().getX() == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony ).getHexCenter()).getX() &&
                                        losonoverlays.overpositiony + (int) losonoverlays.board.getCropBounds().getY()  == (int)(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx , losonoverlays.overpositiony).getHexCenter()).getY()) {
                                    // if center dot on overlay is transparent and aligns with hex center, won't set elevation properly so need to look for nearby terrain type
                                    // bit of a hack but should work - try it until we get a bug
                                    // the bug is with overlays where the border is transparent so test
                                    // (1) if pixel is on the overlay edge and (2) if so are pixels 2 away also transparent
                                    // in those conditions, skip actions
                                    if (!pixelontransparentoverlayborder(losonoverlays)) {
                                        int j = 0;
                                        int k = 0;
                                        elevint = -99;
                                        while ((c >> 24) == 0x00 && j <= 6) {
                                            j += 2;
                                            k += 2;
                                            if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + j, losonoverlays.currenty + k) && pointIsOnOverlay(losonoverlays.bi,losonoverlays.currentx+j, losonoverlays.currenty+k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx + j, losonoverlays.currenty + k);
                                            } else if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + j, losonoverlays.currenty - k) && pointIsOnOverlay(losonoverlays.bi,losonoverlays.currentx+j, losonoverlays.currenty-k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx + j, losonoverlays.currenty - k);
                                            } else if (losonoverlays.newlosdata.onMap(losonoverlays.currentx - j, losonoverlays.currenty + k) && pointIsOnOverlay(losonoverlays.bi,losonoverlays.currentx-j, losonoverlays.currenty+k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx - j, losonoverlays.currenty + k);
                                            } else if (losonoverlays.newlosdata.onMap(losonoverlays.currentx - j, losonoverlays.currenty - k) && pointIsOnOverlay(losonoverlays.bi,losonoverlays.currentx-j, losonoverlays.currenty-k)) {
                                                c = losonoverlays.bi.getRGB(losonoverlays.currentx - j, losonoverlays.currenty - k);
                                            } else {
                                                break;
                                            }
                                            Color color = getRGBColor(c);
                                            //int terrint = board.getVASLBoardArchive().getTerrainForColor(color);
                                            if ((color.equals(Color.white))) {
                                                elevint = 0;
                                            } else {
                                                elevint = losonoverlays.board.getVASLBoardArchive().getElevationForColor(color);
                                            }
                                            //if (terrint >= 0) {
                                            //    terr = newlosdata.getTerrain(terrint);
                                            //}
                                        }
                                        // this sets base elevation for the hex - crest line & depression hexes can contain multiple elevations
                                        if (elevint != -99) {
                                            losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).setBaseHeight(elevint);
                                        }
                                    }
                                }

                            }
                        }
                    }
                }
                addinhterraintolos (inhHexes, losonoverlays, losonoverlays.board);
                addbldglevelstolos(bdgHexes, losonoverlays);
            }
        }
    }

    private void addinhterraintolos(HashMap<Hex, Terrain> inhHexes, LOSonOverlays losonoverlays, ASLBoard board) {
        try {
            for (Hex inhTerrHex : inhHexes.keySet()) {
                Integer terrType = inhHexes.get(inhTerrHex).getType();
                Rectangle s = inhTerrHex.getHexBorder().getBounds();
                for (int i = (int) s.getX(); i < s.getX() + s.getWidth(); i++) {
                    for (int j = (int) s.getY(); j < s.getY() + s.getHeight(); j++) {
                        if(losonoverlays.newlosdata.onMap(i, j)) {
                            if (inhTerrHex.contains(i, j)) {
                                if (board.isReversed()) {
                                    int cropheight = board.getCropBounds().getHeight() == -1 ? (int) board.getUncroppedSize().getHeight() : (int) board.getCropBounds().getHeight();
                                    int cropwidth = board.getCropBounds().getWidth() == -1 ? (int) board.getUncroppedSize().getWidth() : (int) board.getCropBounds().getWidth();
                                    losonoverlays.newlosdata.setGridTerrainCode(terrType, cropwidth - i, cropheight - j);
                                } else {
                                    losonoverlays.newlosdata.setGridTerrainCode(terrType, i - (int) board.getCropBounds().getX(), j - (int) board.getCropBounds().getY());
                                }
                            }
                        }
                    }
                }
                inhTerrHex.getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terrType));
            }
        }catch (Exception e) {
            boolean reg =true;
        }
    }
    private void addbldglevelstolos(HashMap<Hex, Terrain> bdgHexes, LOSonOverlays losonoverlays){
        for(Hex bdglevelHex : bdgHexes.keySet()) {
            bdglevelHex.getCenterLocation().setTerrain(bdgHexes.get(bdglevelHex));
            Terrain centerLocationTerrain = bdglevelHex.getCenterLocation().getTerrain();
            boolean multihex = ismultihex(bdglevelHex, losonoverlays);
                bdglevelHex.addbuildinglevels(centerLocationTerrain, multihex);
        }
    }
    private boolean ismultihex(Hex bdglevelHex, LOSonOverlays losonoverlays){
        boolean multihexbdg = false;
        // find where on overlay the hex is centered
        Point hexcentreonoverlay = new Point();
        hexcentreonoverlay.x = (int) (bdglevelHex.getHexCenter().getX() - losonoverlays.ovrRec.getX());
        hexcentreonoverlay.y = (int) (bdglevelHex.getHexCenter().getY() - losonoverlays.ovrRec.getY());
        // use hex center to test if hexsides contain building pixels
        final double verticalOffset = bdglevelHex.getMap().getHexHeight()/2.0;
        // the hexside point is the hexside center point translated one pixel toward the hex center point
        // [0] is the top hexside and the other points are clock-wise from there
        Point [] hexsidePoints = new Point[6];
        final double horizontalOffset = cos(Math.toRadians(30.0)) * verticalOffset;

        hexsidePoints[0] = new Point ((hexcentreonoverlay.x), (int) (-verticalOffset + hexcentreonoverlay.y + 1.0));
        hexsidePoints[1] = new Point ((int)(horizontalOffset + hexcentreonoverlay.x - 1),  (int) (-verticalOffset/2.0 + hexcentreonoverlay.y + 1.0));
        hexsidePoints[2] = new Point ((int)(horizontalOffset + hexcentreonoverlay.x - 1),  (int) (verticalOffset/2.0 + hexcentreonoverlay.y - 1.0));
        hexsidePoints[3] = new Point (hexcentreonoverlay.x, (int) (verticalOffset + hexcentreonoverlay.y - 1.0));
        hexsidePoints[4] = new Point ((int) (-horizontalOffset + hexcentreonoverlay.x + 1),  (int) (verticalOffset/2.0 + hexcentreonoverlay.y - 1.0));
        hexsidePoints[5] = new Point ((int) (-horizontalOffset + hexcentreonoverlay.x + 1),  (int) (-verticalOffset/2.0 + hexcentreonoverlay.y + 1.0));
        // now test if hexside points contain building colour; if the do, it is multihex building
        for (int i =0; i < 6; i++) {
            int c = losonoverlays.bi.getRGB((int) hexsidePoints[i].getX(), (int) hexsidePoints[i].getY());
            Terrain terr= null;
            if ((c >> 24) != 0x00) { // not a transparent pixel
                String terraintouse = "Open Ground";
                //Retrieving the R G B values
                Color color = getRGBColor(c);
                int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                if (terrint >= 0) {
                    terr = losonoverlays.newlosdata.getTerrain(terrint);
                    terraintouse = terr.getName();
                }
                if (terr != null) {
                    if (terr.isBuilding()) {
                        multihexbdg=true;
                        break;
                    }
                }
            }
        }
        return multihexbdg;
    }
    //set terrain type for point, and center location or hexside location (if hexside terrain)
    private void setterraintype (LOSonOverlays losonoverlays, Terrain terr, String overlaytype) {
        losonoverlays.newlosdata.setGridTerrainCode(terr.getType(), losonoverlays.overpositionx, losonoverlays.overpositiony);
        if (losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getNearestLocation(losonoverlays.overpositionx, losonoverlays.overpositiony).isCenterLocation() && !overlaytype.contains("NoRoads")) {
            losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getCenterLocation().setTerrain(terr);
        } else if (terr != null && terr.isHexsideTerrain()) {
            int hexside = losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getLocationHexside(losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getNearestLocation(losonoverlays.overpositionx, losonoverlays.overpositiony));
            Point hexsidecenter = losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).getHexsideLocation(hexside).getEdgeCenterPoint();
            //only set hexside terrain for hex and hexside location if within 10 pixels of hexside centre - avoids mistaken hexsides
            if (Math.abs(losonoverlays.overpositionx - hexsidecenter.x) < 10 && Math.abs(losonoverlays.overpositiony - hexsidecenter.y) < 10) {
                losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).setHexsideTerrain(hexside, terr);
                losonoverlays.newlosdata.gridToHex(losonoverlays.overpositionx, losonoverlays.overpositiony).setHexsideLocationTerrain(hexside, terr);
            }
        }
    }
    private Terrain getTerrainfromColor(Color color, LOSonOverlays losonoverlays){
        int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
        if (terrint >= 0) {
            return losonoverlays.newlosdata.getTerrain(terrint);
        }
        return null; //newlosdata.getTerrain("Open Ground");
    }
    private Integer getElevationfromColor(LOSonOverlays losonoverlays, Color color){
        int elevint = losonoverlays.board.getVASLBoardArchive().getElevationForColor(color);
        if (elevint == -99) {
            Color newcolor = getnearestcolor(losonoverlays, losonoverlays.overpositionx, losonoverlays.overpositiony);
            if (newcolor == null) { //transparent pixel
                elevint = losonoverlays.newlosdata.getGridElevation(losonoverlays.overpositionx, losonoverlays.overpositiony);
            } else {
                if ((newcolor.equals(Color.white))) {
                    elevint = 0;
                } else {
                    elevint = losonoverlays.board.getVASLBoardArchive().getElevationForColor(newcolor);
                    // this is a hack and may cause errors - test
                    if (elevint == -99) {elevint =0;}
                }
            }
        }
        return elevint;
    }
    private Color getnearestcolor(LOSonOverlays losonoverlays, int newovrx, int newovry){
        int c = 0; int a=2;
        Color color = Color.BLACK;
        while (color.equals(Color.BLACK) || isBoardNumColor(color, losonoverlays) || color.equals(getRGBColor(-5261152)) || color.equals(getRGBColor(-262915))) {  //-5261152 = 175,184,160 - SnowHexDots2
            // point must be (a) on map (b) on overlay (c) not transparent
            if ((losonoverlays.newlosdata.onMap(newovrx + a, newovry + a)) && (pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx+(a-1), losonoverlays.currenty+a) && (!((losonoverlays.bi.getRGB(losonoverlays.currentx+(a-1), losonoverlays.currenty+a) >> 24) == 0X00)))) {
                c = losonoverlays.bi.getRGB(losonoverlays.currentx + (a - 1), losonoverlays.currenty + a);
            } else if ((losonoverlays.newlosdata.onMap(newovrx + a, newovry - a)) && (pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx+(a-1), losonoverlays.currenty-a) && (!((losonoverlays.bi.getRGB(losonoverlays.currentx+(a-1), losonoverlays.currenty-a) >> 24) == 0X00)))) {
                c = losonoverlays.bi.getRGB(losonoverlays.currentx + (a - 1), losonoverlays.currenty - a);
            } else if ((losonoverlays.newlosdata.onMap(newovrx - a, newovry + a)) && (pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx-(a-1), losonoverlays.currenty+a) && (!((losonoverlays.bi.getRGB(losonoverlays.currentx-(a-1), losonoverlays.currenty+a) >> 24) == 0X00)))) {
                c = losonoverlays.bi.getRGB(losonoverlays.currentx - (a - 1), losonoverlays.currenty + a);
            } else if ((losonoverlays.newlosdata.onMap(newovrx - a, newovry - a)) && (pointIsOnOverlay(losonoverlays.bi, losonoverlays.currentx-(a-1), losonoverlays.currenty-a) && (!((losonoverlays.bi.getRGB(losonoverlays.currentx-(a-1), losonoverlays.currenty-a) >> 24) == 0X00)))) {
                c = losonoverlays.bi.getRGB(losonoverlays.currentx - (a - 1), losonoverlays.currenty - a);
            } else {
                c= -5260182;  // use OG as default - see if this causes LOS errors
            }

            color = getRGBColor(c);
            a+=1;
        }
        return color;
    }
    private Boolean pixelontransparentoverlayborder(LOSonOverlays losonoverlays){
        int c = 0;
        int b = 0;
        int a = 3;
        if (losonoverlays.currentx==0 || losonoverlays.currentx== losonoverlays.bi.getWidth()-1) {
            if (losonoverlays.newlosdata.onMap(losonoverlays.overpositionx, losonoverlays.overpositiony + a)) {
                if (losonoverlays.currenty +a > losonoverlays.bi.getHeight()-1 ){ a = -3;}  //need to ensure testing with pixel on overlay
                c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty + a);
                if ((c >> 24) != 0x00) { // not a transparent pixel
                    return false;
                } else {
                    return true;
                }
            }
            if (losonoverlays.newlosdata.onMap(losonoverlays.overpositionx, losonoverlays.overpositiony - a)) {
                if (losonoverlays.currenty - a < 0 ){ a = -3;}  //need to ensure testing with pixel on overlay
                b = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty - a);
                if ((b >> 24) != 0x00) { // not a transparent pixel
                    return false;
                } else {
                    return true;
                }
            }
            return true; //transparent pixel
        } else if (losonoverlays.currenty==0 || losonoverlays.currenty == losonoverlays.bi.getHeight()-1){
            if (losonoverlays.newlosdata.onMap(losonoverlays.overpositionx + a, losonoverlays.overpositiony)) {
                if (losonoverlays.currentx +a > losonoverlays.bi.getWidth()-1 ){ a = -3;}  //need to ensure testing with pixel on overlay
                c = losonoverlays.bi.getRGB(losonoverlays.currentx + a, losonoverlays.currenty);
                if ((c >> 24) != 0x00) { // not a transparent pixel
                    return false;
                } else {
                    return true;
                }
            }
            if (losonoverlays.newlosdata.onMap(losonoverlays.overpositionx -a, losonoverlays.overpositiony)) {
                if (losonoverlays.currentx - a < 0 ){ a = -3;}  //need to ensure testing with pixel on overlay
                b = losonoverlays.bi.getRGB(losonoverlays.currentx-a, losonoverlays.currenty);
                if ((b >> 24) != 0x00) { // not a transparent pixel
                    return false;
                } else {
                    return true;
                }
            }
            return true; //transparent pixel
        }
        return false;  // not on border
    }
    private boolean pointIsOnOverlay(BufferedImage bi, int usex, int usey){
        return usex >= 0 && usex < bi.getWidth() && usey >= 0 && usey < bi.getHeight();
    }
    //add Hex to collections of inherent hexes and building hexes on the overlay
    private void addHextoInhandBldgMaps(Terrain terr, LOSonOverlays losonoverlays, HashMap<VASL.LOS.Map.Hex, VASL.LOS.Map.Terrain>  inhHexes, HashMap<VASL.LOS.Map.Hex, VASL.LOS.Map.Terrain> bdgHexes) {

        if (terr != null) {
            if (terr.isInherentTerrain()) {
                if (!inhHexes.containsKey(losonoverlays.newlosdata.gridToHex((int) losonoverlays.overpositionx, (int) losonoverlays.overpositiony))) {
                    //hack - ensure that the pixel is not close to a hexside as VASL geometry can put it in an adjacent hex
                    Point hexcenter =losonoverlays.newlosdata.gridToHex((int) losonoverlays.overpositionx, (int) losonoverlays.overpositiony).getHexCenter();
                    Double d=  Math.sqrt(((Math.pow(hexcenter.x - losonoverlays.overpositionx, 2) + (Math.pow(hexcenter.y - losonoverlays.overpositiony, 2)))));
                    if (d <25) {
                        inhHexes.put(losonoverlays.newlosdata.gridToHex((int) losonoverlays.overpositionx, (int) losonoverlays.overpositiony), terr);
                    }
                }
            } else if (terr.isBuilding()) {
                if (!terr.getName().equals("Stone Building") && !terr.getName().equals("Wooden Building") && !terr.getName().contains("Rowhouse Wall")) {
                    if (!bdgHexes.containsKey(losonoverlays.newlosdata.gridToHex((int) losonoverlays.overpositionx, (int) losonoverlays.overpositiony))) {
                        bdgHexes.put(losonoverlays.newlosdata.gridToHex((int) losonoverlays.overpositionx, (int) losonoverlays.overpositiony), terr);
                    }
                }
            }
        }
    }

    private boolean isBoardNumColor(Color testcolor, LOSonOverlays losonoverlays){
        if(testcolor == null) {
            return false;
        }
        final String colorName = losonoverlays.board.getVASLBoardArchive().getVASLColorName(testcolor);
        return "WhiteHexNumbers".equals(colorName) || "WinterBlackHexNumbers".equals(colorName) ||
                "MudBoardNum".equals(colorName) || "DTO_BoardNum".equals(colorName) ||
                "AD_WinterBlackHexNumbers".equals(colorName);
    }
    // if overlayname returns "" from this method then los checking won't work with the overlay
    // when adding items here also add them to VASLThread.initializeMap
    private String getoverlayterraintype(Overlay o){
        String overlayname = o.getName();
        if (overlayname.contains("elrr")){
            return "Elevated Railroad";
        }
        if (overlayname.contains("surr")){
            return "Sunken Railroad";
        }
        if (overlayname.contains("rr")){
            return "Railroad";
        }
        if (overlayname.contains("NoRoads")){
            return "NoRoads";
        }
        if (overlayname.contains("rv")){
            return "River";
        }
        if (overlayname.contains("sw")){
            return "Swamp";
        }
        if (overlayname.contains("be")){
            return "Beach";
        }
        if (overlayname.contains("b")){
            return "Brush";
        }
        if (overlayname.contains("hd")){
            return "Hedges";
        }
        if (overlayname.contains("sh")){
            return "Shellholes";
        }
        else if (overlayname.contains("og") || overlayname.equals("dx1") || overlayname.equals("dx5")){
            return "Open Ground";
        }
        else if (overlayname.contains("m")){
            return "Marsh";
        }
        else if (overlayname.contains("g")){
            return "Grain";
        }
        else if (overlayname.contains("p")){
            return "Water";
        }
        else if (overlayname.contains("ow")){
            return "Woods";
        }
        else if (overlayname.contains("oc")){
            return "Ocean";
        }
        else if (overlayname.contains("o") || overlayname.equals("dx3") || overlayname.equals("dx7")){
            return "Orchard";
        }
        else if (overlayname.contains("wd") || overlayname.equals("dx2") || overlayname.equals("dx4")){
            return "Woods";
        }
        else if (overlayname.contains("x") && !overlayname.contains("dx")) {
            return "Building";
        }
        else if (overlayname.contains("hi")) {
            return "Hill";
        }
        else if (overlayname.contains("st")) {
            return "Stream";
        }
        else if (overlayname.contains("sr")) {
            return "Stone Rubble";
        }
        else if (overlayname.contains("wr")) {
            return "Wooden Rubble";
        }
        else if (overlayname.contains("wt")) {
            return "Water";
        }
        else if (overlayname.contains("v")) {
            return "Vineyard";
        }
        else {
            return "";
        }

    }

    private boolean isInherenttype(String terraintype){
        return (terraintype.equals("Orchard") || terraintype.contains("Stone Rubble") || terraintype.contains("Wooden Rubble"));
    }
    private void setOverlayInherentTerrain(LOSonOverlays losonoverlays, String terraintype){
        Hex temphex = null; Hex newhex;
        Hex previoushex = null;

        if (losonoverlays.board.isReversed()){
            // flip the overlay grid
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x  -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1)) {
                        Hex hextouse = losonoverlays.newlosdata.gridToHex(losonoverlays.newlosdata.getGridWidth() - losonoverlays.ovrRec.x -losonoverlays.currentx -1, losonoverlays.newlosdata.getGridHeight() - losonoverlays.ovrRec.y -losonoverlays.currenty -1);
                        if (!hextouse.equals(previoushex)) {
                            int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                            if ((c >> 24) != 0x00) { // not a transparent pixel
                                String terraintouse = "Open Ground";
                                Terrain terr = null;
                                //Retrieving the R G B values
                                Color color = getRGBColor(c);
                                int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                                if (terrint >= 0) {
                                    terr = losonoverlays.newlosdata.getTerrain(terrint);
                                    if (terr.getName().equals(terraintype)) {
                                        terraintouse = terraintype;
                                    }
                                }
                                if (!terraintouse.equals("Open Ground") && terr != null) {  // terrain is inherent terrain

                                    hextouse.getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terraintouse));
                                    hextouse.setoverlayborder();
                                    LOSDataEditor loseditor = new LOSDataEditor(losonoverlays.newlosdata);
                                    loseditor.setGridTerrain(hextouse.getoverlayborder(), terr);
                                    for (int z = 0; z < 6; z++) {
                                        hextouse.setHexsideTerrain(z, losonoverlays.newlosdata.getTerrain("Open Ground"));
                                        Hex adjhex = losonoverlays.newlosdata.getAdjacentHex(hextouse, z);
                                        if (adjhex != null) {
                                            adjhex.setHexsideTerrain(Hex.getOppositeHexside(z), losonoverlays.newlosdata.getTerrain("Open Ground"));
                                        }
                                    }
                                    previoushex = hextouse;
                                }
                            }

                            //newlosdata.setGridTerrainCode(newlosdata.getTerrain(terraintouse).getType(), newlosdata.getGridWidth() - ovrRec.x  -x -1, newlosdata.getGridHeight() - ovrRec.y -y -1);
                            //if (newlosdata.gridToHex(newlosdata.getGridWidth() - ovrRec.x  -x -1, newlosdata.getGridHeight() - ovrRec.y -y -1).getNearestLocation(newlosdata.getGridWidth() - ovrRec.x  -y -1, newlosdata.getGridHeight() - ovrRec.y -x -1).isCenterLocation()) {
                            //    newlosdata.gridToHex(newlosdata.getGridWidth() - ovrRec.x  -x -1, newlosdata.getGridHeight() - ovrRec.y -y -1).getCenterLocation().setTerrain(newlosdata.getTerrain(terraintouse));
                            //}
                        }
                    }
                }
            }
        } else {
            for (losonoverlays.currentx = 0; losonoverlays.currentx < losonoverlays.bi.getWidth(); losonoverlays.currentx++) {
                for (losonoverlays.currenty = 0; losonoverlays.currenty < losonoverlays.bi.getHeight(); losonoverlays.currenty++) {
                    if (losonoverlays.newlosdata.onMap(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y)) {
                        Hex hextouse = losonoverlays.newlosdata.gridToHex(losonoverlays.currentx + losonoverlays.ovrRec.x, losonoverlays.currenty + losonoverlays.ovrRec.y);
                        if (!hextouse.equals(previoushex)) {
                            int c = losonoverlays.bi.getRGB(losonoverlays.currentx, losonoverlays.currenty);
                            if ((c >> 24) != 0x00) { // not a transparent pixel
                                String terraintouse = "Open Ground";
                                Terrain terr = null;
                                //Retrieving the R G B values
                                Color color = getRGBColor(c);
                                int terrint = losonoverlays.board.getVASLBoardArchive().getTerrainForColor(color);
                                if (terrint >= 0) {
                                    terr = losonoverlays.newlosdata.getTerrain(terrint);
                                    if (terr.getName().equals(terraintype)) {
                                        terraintouse = terraintype;
                                    }
                                }

                                if (!terraintouse.equals("Open Ground") && terr != null) {  // terrain is inherent terrain

                                    hextouse.getCenterLocation().setTerrain(losonoverlays.newlosdata.getTerrain(terraintouse));
                                    hextouse.setoverlayborder();
                                    LOSDataEditor loseditor = new LOSDataEditor(losonoverlays.newlosdata);
                                    loseditor.setGridTerrain(hextouse.getoverlayborder(), terr);
                                    for (int z = 0; z < 6; z++) {
                                        hextouse.setHexsideTerrain(z, losonoverlays.newlosdata.getTerrain("Open Ground"));
                                        Hex adjhex = losonoverlays.newlosdata.getAdjacentHex(hextouse, z);
                                        if (adjhex != null) {
                                            adjhex.setHexsideTerrain(Hex.getOppositeHexside(z), losonoverlays.newlosdata.getTerrain("Open Ground"));
                                        }
                                    }
                                    previoushex = hextouse;
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    /**
     * Sets status of LOS engine to legacy mode
     */
    private void setLegacyMode() {
        legacyMode = true;
        VASLMap = null;

    }
    /**
     * @return the VASL map
     */
    public VASL.LOS.Map.Map getVASLMap(){
        return VASLMap;
    }

    /**
     * @return the shared board metadata
     */
    public static SharedBoardMetadata getSharedBoardMetadata() {
        return sharedBoardMetadata;
    }

    /**
     * @return the counter metadata
     */
    public static CounterMetadataFile getCounterMetadata() {
        return counterMetadata;
    }

    /**
     * @return true if the map is in legacy mode (i.e. pre-6.0)
     */
    public boolean isLegacyMode(){
        return legacyMode;
    }

    /**
     * Log a string to the VASSAL error log
     * @param error the error string
     */
    private void logError(String error) {
        logger.info(error);
    }

    /**
     * Log an exception to the VASSAL error log
     * @param error the exception
     */
    private void logException(Throwable error) {
        logger.info("", error);
    }

    public BufferedImage getImgMapIcon(Point pt, double size, double os_scale) {
        BufferedImage img = new BufferedImage((int)size, (int)size, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2d = img.createGraphics();

        double dzoom = 0.0;
        Rectangle rect = null;

        for (Board b: getBoards()) {
            if (rect == null) {
                final double mag = b.getMagnification();
                dzoom = os_scale / mag;

                rect = new Rectangle(
                  (int)((pt.x * os_scale - size/2) / mag),
                  (int)((pt.y * os_scale - size/2) / mag),
                  (int)(size / mag),
                  (int)(size / mag)
                );

                g2d.translate(-rect.x, -rect.y);
            }

            b.drawRegion(g2d, getLocation(b, dzoom), rect, dzoom, null);
        }

        drawPiecesNonStackableInRegion(g2d, rect, dzoom);

        g2d.dispose();
        return img;
    }

  protected void drawPiecesNonStackableInRegion(Graphics g, Rectangle visibleRect, double dZoom)
  {
      Graphics2D g2d = (Graphics2D) g;
      Composite oldComposite = g2d.getComposite();
      g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pieceOpacity));
      
      GamePiece[] stack = pieces.getPieces();
      
      for (int i = 0; i < stack.length; ++i) 
      {
        Point pt = stack[i].getPosition();
        
        if (stack[i].getClass() != Stack.class) 
        {
          if (Boolean.TRUE.equals(stack[i].getProperty(Properties.NO_STACK)))
          {
              //JY
              //stack[i].draw(g, (int) (pt.x * dZoom), (int) (pt.y * dZoom), null, dZoom);
              double pZoom = PieceScalerBoardZoom(stack[i]);
              stack[i].draw(g, (int) (pt.x * dZoom), (int) (pt.y * dZoom), null, dZoom*pZoom);
              //JY
          }
        }
      }
      
      g2d.setComposite(oldComposite);
  }    
  
  public void setShowMapLevel(ShowMapLevel showMapLevel) {
      m_showMapLevel = showMapLevel;
  }
    
    @Override
  public boolean isPiecesVisible() {
    return pieceOpacity != 0;
  }   
  
    @Override
    public void drawPiecesInRegion(Graphics g,
                                 Rectangle visibleRect,
                                 Component c) {

    Graphics2D g2d = (Graphics2D) g;
    Composite oldComposite = g2d.getComposite();
    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pieceOpacity));

    final double os_scale = g2d.getDeviceConfiguration().getDefaultTransform().getScaleX();
    final double dzoom = getZoom() * os_scale;

    GamePiece[] stack = pieces.getPieces();

    for (int i = 0; i < stack.length; ++i)
    {
        Point pt = mapToDrawing(stack[i].getPosition(), os_scale);
        //JY
        double pZoom = PieceScalerBoardZoom(stack[i]);
        //JY

        if (stack[i].getClass() == Stack.class)
        {
            if (m_showMapLevel == ShowMapLevel.ShowAll)
                //JY
                //getStackMetrics().draw((Stack) stack[i], pt, g, this, dzoom, visibleRect);
                //Draw the pieces with a separate zoom from the board, and adjust the stack spacing accordingly
                getStackMetrics().setAttribute("unexSepX", Integer.toString(unexSepX));
            getStackMetrics().setAttribute("unexSepY", Integer.toString(unexSepY));
            getStackMetrics().setAttribute("exSepX", Integer.toString(exSepX));
            getStackMetrics().setAttribute("exSepY", Integer.toString(exSepY));
            getStackMetrics().draw((Stack) stack[i], pt, g, this, dzoom*pZoom, visibleRect);
            //JY
                //getStackMetrics().draw((Stack) stack[i], pt, g, this, dzoom, visibleRect);
        }
        else
        {
            if (m_showMapLevel == ShowMapLevel.ShowAll  || (stack[i].getProperty("overlay") != null && m_showMapLevel == ShowMapLevel.ShowMapOnly)) // always show overlays
            {
                //JY
                //stack[i].draw(g, pt.x, pt.y, c, dzoom);
                stack[i].draw(g, pt.x, pt.y, c, dzoom*pZoom);
                //JY

                if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                    //JY
                    // highlighter.draw(stack[i], g, pt.x, pt.y, c, dzoom);
                    highlighter.draw(stack[i], g, pt.x, pt.y, c, dzoom*pZoom);
                //JY

            }
            else if (m_showMapLevel == ShowMapLevel.ShowMapAndOverlay)
            {
                if (Boolean.TRUE.equals(stack[i].getProperty(Properties.NO_STACK)))
                {
                    //JY
                    //stack[i].draw(g, pt.x, pt.y, c, dzoom);
                    stack[i].draw(g, pt.x, pt.y, c, dzoom*pZoom);
                    //JY

                    if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                        //JY
                        //highlighter.draw(stack[i], g, pt.x, pt.y, c, dzoom);
                        highlighter.draw(stack[i], g, pt.x, pt.y, c, dzoom*pZoom);
                    //JY
                }
            }
        }
/*
        // draw bounding box for debugging
        final Rectangle bb = stack[i].boundingBox();
        g.drawRect(pt.x + bb.x, pt.y + bb.y, bb.width, bb.height);
*/
        }
        
        g2d.setComposite(oldComposite); 
    }

    @Override
  public void drawPieces(Graphics g, int xOffset, int yOffset) 
  {

    Graphics2D g2d = (Graphics2D) g;
    Composite oldComposite = g2d.getComposite();
    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, pieceOpacity));

    final double os_scale = g2d.getDeviceConfiguration().getDefaultTransform().getScaleX();
    final double dzoom = getZoom() * os_scale;

    GamePiece[] stack = pieces.getPieces();

    for (int i = 0; i < stack.length; ++i)
    {
        //JY
        double pZoom = PieceScalerBoardZoom(stack[i]);
        //JY
        if (m_showMapLevel == ShowMapLevel.ShowAll || (stack[i].getProperty("overlay") != null && m_showMapLevel == ShowMapLevel.ShowMapOnly)) // always show overlays
        {
            Point pt = mapToDrawing(stack[i].getPosition(), os_scale);

            //JY
            //stack[i].draw(g, pt.x + xOffset, pt.y + yOffset, theMap, dzoom);
            stack[i].draw(g, pt.x + xOffset, pt.y + yOffset, theMap, dzoom*pZoom);
            //JY

            if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                //JY
                //highlighter.draw(stack[i], g, pt.x - xOffset, pt.y - yOffset, theMap, dzoom);
                highlighter.draw(stack[i], g, pt.x - xOffset, pt.y - yOffset, theMap, dzoom*pZoom);
            //JY
        }
        else if (m_showMapLevel == ShowMapLevel.ShowMapAndOverlay)
        {
            if (stack[i].getClass() != Stack.class)
            {
                if (Boolean.TRUE.equals(stack[i].getProperty(Properties.NO_STACK)))
                {
                    Point pt = mapToDrawing(stack[i].getPosition(), os_scale);

                    //JY
                    //stack[i].draw(g, pt.x + xOffset, pt.y + yOffset, theMap, dzoom);
                    stack[i].draw(g, pt.x + xOffset, pt.y + yOffset, theMap, dzoom*pZoom);
                    //JY

                    if (Boolean.TRUE.equals(stack[i].getProperty(Properties.SELECTED)))
                        //JY
                        //highlighter.draw(stack[i], g, pt.x - xOffset, pt.y - yOffset, theMap, dzoom);
                        highlighter.draw(stack[i], g, pt.x - xOffset, pt.y - yOffset, theMap, dzoom*pZoom);
                    //JY
                }
            }
        }
    }

    g2d.setComposite(oldComposite);
  }
    private Color getRGBColor(int c){
        int red = (c & 0x00ff0000) >> 16;
        int green = (c & 0x0000ff00) >> 8;
        int blue = c & 0x000000ff;
        return new Color(red, green, blue);
    }
  
  public enum ShowMapLevel
  {
      ShowAll,
      ShowMapAndOverlay,
      ShowMapOnly        
  }

    private void findOverlays() {
        //All pieces that should scale with the map, not the counters
        //Mostly overlays, but some others
        String[] ovlPalettes = {"Draggable Overlays", "Deluxe Draggable", "Terrain Overlays", "Overlays (Large)"};
        java.util.List<PieceWindow> pwList = GameModule.getGameModule().getAllDescendantComponentsOf(PieceWindow.class);
        for (PieceWindow pw: pwList) {
            String pwName = pw.getAttributeValueString("name");
            if (Arrays.asList(ovlPalettes).contains(pwName)) {
                java.util.List<PieceSlot> psList = pw.getAllDescendantComponentsOf(PieceSlot.class);
                for (PieceSlot ps: psList) {
                    PieceSlotGpIdList.add(ps.getGpId());
                }
            }
        }
        String[] ovlPanels = {"Phase Track", "Turn Markers"};
        java.util.List<PanelWidget> panList = GameModule.getGameModule().getAllDescendantComponentsOf(PanelWidget.class);
        for (PanelWidget pw: panList) {
            String pwName = pw.getAttributeValueString("entryName");
            if (Arrays.asList(ovlPanels).contains(pwName)) {
                java.util.List<PieceSlot> psList = pw.getAllDescendantComponentsOf(PieceSlot.class);
                for (PieceSlot ps: psList) {
                    PieceSlotGpIdList.add(ps.getGpId());
                }
            }
        }
        String[] ovlScrolls = {"< Turn Tracks", "Turn Markers (by Module)"};
        java.util.List<ListWidget> scrList = GameModule.getGameModule().getAllDescendantComponentsOf(ListWidget.class);
        for (ListWidget pw: scrList) {
            String pwName = pw.getAttributeValueString("entryName");
            if (Arrays.asList(ovlScrolls).contains(pwName)) {
                java.util.List<PieceSlot> psList = pw.getAllDescendantComponentsOf(PieceSlot.class);
                for (PieceSlot ps: psList) {
                    PieceSlotGpIdList.add(ps.getGpId());
                }
            }
        }
    }

    private void createDeluxeBoardsList() {
        dxAvailBoards = VASL.build.module.map.BoardDataReader.getDeluxeBoardNamesList();
    }

    public double PieceScalerBoardZoom(GamePiece gp) {
        //Test if piece is an overlay or otherwise should scale with the board
        boolean keepAtBoardScale = false;
        if (gp instanceof Stack) {
            Stack s = (Stack) gp;
            for (int i = s.getPieceCount(); i > 0; i--) { //Top down in the stack
                GamePiece sgp = s.getPieceAt(i - 1);
                if (PieceSlotGpIdList.contains(sgp.getProperty(Properties.PIECE_ID).toString()) || (sgp.getProperty(SCALEWITHBOARDZOOM) != null)) {
                    keepAtBoardScale = true;
                }
            }
        }
        else {
            if (PieceSlotGpIdList.contains(gp.getProperty(Properties.PIECE_ID).toString()) || (gp.getProperty(SCALEWITHBOARDZOOM) != null)) {
                keepAtBoardScale = true;
            }
        }
        return (keepAtBoardScale? 1.0D : 1.0D/getbZoom())*PieceScalerBoardMag(gp);
    }

    public double PieceScalerBoardMag(GamePiece gp) {
        //Look for pieces that should get additional zoom due to board magnification level
        double magZoom = 1.0;
        double mag = 1.0;
        for (Board b: getBoards()) {
            mag = b.getMagnification();
        }
        boolean deluxe = false;
        for (Board b: getBoards()) {
            String bdName = b.getName();
            deluxe = dxAvailBoards.contains(bdName);
        }
        if (deluxe) {mag = mag*3.0;}

        if (gp instanceof Stack) {
            Stack s = (Stack) gp;
            for (int i = s.getPieceCount(); i > 0; i--) { //Top down in the stack
                GamePiece sgp = s.getPieceAt(i - 1);
                if ((sgp.getProperty(SCALEWITHBOARDMAG) != null) && (mag != 1.0)) {
                    magZoom = mag;
                }
            }
        }
        else {
            if ((gp.getProperty(SCALEWITHBOARDMAG) != null) && (mag != 1.0)) {
                magZoom = mag;
            }
        }
        return magZoom;
    }
}
