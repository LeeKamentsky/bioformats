//
// InCellReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.xml.XMLTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.MetadataStore;

import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PositiveInteger;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * InCellReader is the file format reader for InCell 1000/2000 datasets.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://dev.loci.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/InCellReader.java">Trac</a>,
 * <a href="http://dev.loci.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/InCellReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert melissa at glencoesoftware.com
 */
public class InCellReader extends FormatReader {

  // -- Constants --

  public static final String INCELL_MAGIC_STRING = "IN Cell Analyzer";

  private static final String[] PIXELS_SUFFIXES =
    new String[] {"tif", "tiff", "im"};
  private static final String[] METADATA_SUFFIXES =
    new String[] {"xdce", "xml", "xlog"};

  // -- Fields --

  private boolean[][] plateMap;

  private Image[][][][] imageFiles;
  private MinimalTiffReader tiffReader;
  private Vector<Integer> emWaves, exWaves;
  private Vector<String> channelNames;
  private int totalImages;
  private int imageWidth, imageHeight;
  private String creationDate;
  private String rowName = "A", colName = "1";
  private int fieldCount;

  private int wellRows, wellCols;
  private Hashtable<Integer, int[]> wellCoordinates;
  private Vector<Double> posX, posY;

  private boolean[][] exclude;

  private Vector<Integer> channelsPerTimepoint;
  private boolean oneTimepointPerSeries;
  private int totalChannels;

  private Vector<String> metadataFiles;

  // -- Constructor --

  /** Constructs a new InCell 1000/2000 reader. */
  public InCellReader() {
    super("InCell 1000/2000",
      new String[] {"xdce", "xml", "tiff", "tif", "xlog", "im"});
    suffixSufficient = false;
    domains = new String[] {FormatTools.HCS_DOMAIN};
    hasCompanionFiles = true;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    if (checkSuffix(name, PIXELS_SUFFIXES) || checkSuffix(name, "xlog") && open)
    {
      Location file = new Location(name).getAbsoluteFile().getParentFile();
      String[] list = file.list(true);
      for (String f : list) {
        if (checkSuffix(f, new String[] {"xdce", "xml"})) {
          return isThisType(new Location(file, f).getAbsolutePath(), open);
        }
      }
    }
    return super.isThisType(name, open);
  }

  /* @see loci.formats.IFormatReader#isSingleFile(String) */
  public boolean isSingleFile(String id) throws FormatException, IOException {
    return false;
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    final int blockLen = 2048;
    if (!FormatTools.validStream(stream, blockLen, false)) return false;
    String check = stream.readString(blockLen);
    return check.indexOf(INCELL_MAGIC_STRING) >= 0;
  }

  /* @see loci.formats.IFormatReader#fileGroupOption(String) */
  public int fileGroupOption(String id) throws FormatException, IOException {
    return FormatTools.MUST_GROUP;
  }

  /* @see loci.formats.IFormatReader#get8BitLookupTable() */
  public byte[][] get8BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    return tiffReader == null ? null : tiffReader.get8BitLookupTable();
  }

  /* @see loci.formats.IFormatReader#get16BitLookupTable() */
  public short[][] get16BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    return tiffReader == null ? null : tiffReader.get16BitLookupTable();
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    int[] coordinates = getZCTCoords(no);

    int well = getWellFromSeries(getSeries());
    int field = getFieldFromSeries(getSeries());
    int timepoint = oneTimepointPerSeries ?
      getSeries() % channelsPerTimepoint.size() : coordinates[2];
    int image = getIndex(coordinates[0], coordinates[1], 0);

    if (imageFiles[well][field][timepoint][image] == null) return buf;
    String filename = imageFiles[well][field][timepoint][image].filename;
    if (filename == null || !(new Location(filename).exists())) return buf;

    if (imageFiles[well][field][timepoint][image].isTiff) {
      tiffReader.setId(filename);
      return tiffReader.openBytes(0, buf, x, y, w, h);
    }

    // pixels are stored in .im files
    RandomAccessInputStream s = new RandomAccessInputStream(filename);
    s.skipBytes(128);
    readPlane(s, x, y, w, h, buf);
    s.close();
    return buf;
  }

  /* @see loci.formats.IFormatReader#getSeriesUsedFiles(boolean) */
  public String[] getSeriesUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);
    Vector<String> files = new Vector<String>();
    files.addAll(metadataFiles);
    if (!noPixels && imageFiles != null) {
      int well = getWellFromSeries(getSeries());
      int field = getFieldFromSeries(getSeries());
      for (Image[] timepoints : imageFiles[well][field]) {
        for (Image plane : timepoints) {
          if (plane != null && plane.filename != null) {
            if (new Location(plane.filename).exists()) {
              files.add(plane.filename);
            }
            if (new Location(plane.thumbnailFile).exists()) {
              files.add(plane.thumbnailFile);
            }
          }
        }
      }
    }
    return files.toArray(new String[files.size()]);
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (tiffReader != null) tiffReader.close(fileOnly);

    if (!fileOnly) {
      imageFiles = null;
      tiffReader = null;
      totalImages = 0;
      emWaves = exWaves = null;
      channelNames = null;
      wellCoordinates = null;
      posX = null;
      posY = null;
      creationDate = null;
      wellRows = wellCols = 0;
      fieldCount = 0;
      exclude = null;
      metadataFiles = null;
      imageWidth = imageHeight = 0;
      rowName = "A";
      colName = "1";
      channelsPerTimepoint = null;
      oneTimepointPerSeries = false;
      totalChannels = 0;
      plateMap = null;
    }
  }

  /* @see loci.formats.IFormatReader#getOptimalTileWidth() */
  public int getOptimalTileWidth() {
    FormatTools.assertId(currentId, true, 1);
    if (imageFiles[0][0][0][0].isTiff) {
      return tiffReader.getOptimalTileWidth();
    }
    return super.getOptimalTileWidth();
  }

  /* @see loci.formats.IFormatReader#getOptimalTileHeight() */
  public int getOptimalTileHeight() {
    FormatTools.assertId(currentId, true, 1);
    if (imageFiles[0][0][0][0].isTiff) {
      return tiffReader.getOptimalTileHeight();
    }
    return super.getOptimalTileHeight();
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    // make sure that we have the .xdce (or .xml) file
    if (checkSuffix(id, PIXELS_SUFFIXES) || checkSuffix(id, "xlog")) {
      Location parent = new Location(id).getAbsoluteFile().getParentFile();
      String[] list = parent.list(true);
      for (String f : list) {
        if (checkSuffix(f, new String[] {"xdce", "xml"})) {
          String path = new Location(parent, f).getAbsolutePath();
          if (isThisType(path)) {
            id = path;
            break;
          }
        }
      }
    }

    super.initFile(id);
    in = new RandomAccessInputStream(id);

    channelNames = new Vector<String>();
    emWaves = new Vector<Integer>();
    exWaves = new Vector<Integer>();
    channelsPerTimepoint = new Vector<Integer>();
    metadataFiles = new Vector<String>();

    // build list of companion files

    Location directory = new Location(id).getAbsoluteFile().getParentFile();
    String[] files = directory.list(true);
    for (String file : files) {
      if (checkSuffix(file, METADATA_SUFFIXES)) {
        metadataFiles.add(new Location(directory, file).getAbsolutePath());
      }
    }

    // parse metadata from the .xdce or .xml file

    wellCoordinates = new Hashtable<Integer, int[]>();
    posX = new Vector<Double>();
    posY = new Vector<Double>();

    byte[] b = new byte[(int) in.length()];
    in.read(b);

    core[0].dimensionOrder = "XYZCT";

    MetadataStore store = makeFilterMetadata();
    DefaultHandler handler = new MinimalInCellHandler();
    XMLTools.parseXML(b, handler);

    if (getSizeZ() == 0) core[0].sizeZ = 1;
    if (getSizeC() == 0) core[0].sizeC = 1;
    if (getSizeT() == 0) core[0].sizeT = 1;

    int seriesCount = 0;

    if (exclude != null) {
      for (int row=0; row<wellRows; row++) {
        for (int col=0; col<wellCols; col++) {
          if (!exclude[row][col]) {
            seriesCount += imageFiles[row*wellCols + col].length;
          }
        }
      }
      int expectedSeries = totalImages / (getSizeZ() * getSizeC() * getSizeT());
      seriesCount = (int) Math.min(seriesCount, expectedSeries);
    }
    else seriesCount = totalImages / (getSizeZ() * getSizeC() * getSizeT());

    totalChannels = getSizeC();

    oneTimepointPerSeries = false;
    for (int i=1; i<channelsPerTimepoint.size(); i++) {
      if (!channelsPerTimepoint.get(i).equals(channelsPerTimepoint.get(i - 1)))
      {
        oneTimepointPerSeries = true;
        break;
      }
    }
    if (oneTimepointPerSeries) {
      int imageCount = 0;
      for (Integer timepoint : channelsPerTimepoint) {
        imageCount += timepoint.intValue() * getSizeZ();
      }
      seriesCount = (totalImages / imageCount) * getSizeT();
    }

    int sizeT = getSizeT();
    int sizeC = getSizeC();
    int z = getSizeZ();
    int t = oneTimepointPerSeries ? 1 : getSizeT();

    core = new CoreMetadata[seriesCount];
    for (int i=0; i<seriesCount; i++) {
      int c = oneTimepointPerSeries ?
        channelsPerTimepoint.get(i % sizeT).intValue() : sizeC;

      core[i] = new CoreMetadata();
      core[i].sizeZ = z;
      core[i].sizeC = c;
      core[i].sizeT = t;
      core[i].imageCount = z * c * t;
      core[i].dimensionOrder = "XYZCT";
    }

    int wellIndex = getWellFromSeries(0);
    int fieldIndex = getFieldFromSeries(0);

    String filename = imageFiles[wellIndex][fieldIndex][0][0].filename;
    boolean isTiff = imageFiles[wellIndex][fieldIndex][0][0].isTiff;

    if (isTiff && filename != null) {
      tiffReader = new MinimalTiffReader();
      tiffReader.setId(filename);
      int nextTiming = 0;
      for (int i=0; i<seriesCount; i++) {
        core[i].sizeX = tiffReader.getSizeX();
        core[i].sizeY = tiffReader.getSizeY();
        core[i].interleaved = tiffReader.isInterleaved();
        core[i].indexed = tiffReader.isIndexed();
        core[i].rgb = tiffReader.isRGB();
        core[i].pixelType = tiffReader.getPixelType();
        core[i].littleEndian = tiffReader.isLittleEndian();
      }
    }
    else {
      for (int i=0; i<seriesCount; i++) {
        core[i].sizeX = imageWidth;
        core[i].sizeY = imageHeight;
        core[i].interleaved = false;
        core[i].indexed = false;
        core[i].rgb = false;
        core[i].pixelType = FormatTools.UINT16;
        core[i].littleEndian = true;
      }
    }

    MetadataTools.populatePixels(store, this, true);

    if (getMetadataOptions().getMetadataLevel() != MetadataLevel.MINIMUM) {
      handler = new InCellHandler(store);
      XMLTools.parseXML(b, handler);
    }

    // populate Image data

    String instrumentID = MetadataTools.createLSID("Instrument", 0);
    String experimentID = MetadataTools.createLSID("Experiment", 0);
    store.setInstrumentID(instrumentID, 0);
    store.setExperimentID(experimentID, 0);

    for (int i=0; i<seriesCount; i++) {
      int well = getWellFromSeries(i);
      int field = getFieldFromSeries(i) + 1;
      int totalTimepoints =
        oneTimepointPerSeries ? channelsPerTimepoint.size() : 1;
      int timepoint = oneTimepointPerSeries ? (i % totalTimepoints) + 1 : -1;

      String imageID = MetadataTools.createLSID("Image", i);
      store.setImageID(imageID, i);
      store.setImageInstrumentRef(instrumentID, i);
      store.setImageExperimentRef(experimentID, i);

      int wellRow = well / wellCols;
      int wellCol = well % wellCols;

      char rowChar = rowName.charAt(rowName.length() - 1);
      char colChar = colName.charAt(colName.length() - 1);
      String row = rowName.substring(0, rowName.length() - 1);
      String col = colName.substring(0, colName.length() - 1);

      if (Character.isDigit(rowChar)) {
        row += wellRow + Integer.parseInt(String.valueOf(rowChar));
      }
      else row += (char) (rowChar + wellRow);

      if (Character.isDigit(colChar)) {
        col += wellCol + Integer.parseInt(String.valueOf(colChar));
      }
      else col += (char) (colChar + wellCol);

      String imageName = "Well " + row + "-" + col + ", Field #" + field;
      if (timepoint >= 0) {
        imageName += ", Timepoint #" + timepoint;
      }

      store.setImageName(imageName, i);
      store.setImageAcquiredDate(creationDate, i);

      timepoint--;
      if (timepoint < 0) timepoint = 0;
      int sampleIndex = (field - 1) * totalTimepoints + timepoint;

      String wellSampleID =
        MetadataTools.createLSID("WellSample", 0, well, sampleIndex);
      store.setWellSampleID(wellSampleID, 0 ,well, sampleIndex);
      store.setWellSampleIndex(new NonNegativeInteger(i), 0, well, sampleIndex);
      store.setWellSampleImageRef(imageID, 0, well, sampleIndex);
      if (field < posX.size()) {
        store.setWellSamplePositionX(posX.get(field), 0, well, sampleIndex);
      }
      if (field < posY.size()) {
        store.setWellSamplePositionY(posY.get(field), 0, well, sampleIndex);
      }
    }

    if (getMetadataOptions().getMetadataLevel() != MetadataLevel.MINIMUM) {
      // populate PlaneTiming data

      for (int i=0; i<seriesCount; i++) {
        int well = getWellFromSeries(i);
        int field = getFieldFromSeries(i);
        int timepoint = oneTimepointPerSeries ?
          i % channelsPerTimepoint.size() : 0;
        for (int time=0; time<getSizeT(); time++) {
          if (!oneTimepointPerSeries) timepoint = time;
          int c = channelsPerTimepoint.get(timepoint).intValue();
          for (int q=0; q<getSizeZ()*c; q++) {
            Image img = imageFiles[well][field][timepoint][q];
            if (img == null) continue;
            int plane = time * getSizeZ() * c + q;
            store.setPlaneDeltaT(img.deltaT, i, plane);
            store.setPlaneExposureTime(img.exposure, i, plane);

            store.setPlanePositionX(posX.get(field), i, plane);
            store.setPlanePositionY(posY.get(field), i, plane);
            store.setPlanePositionZ(img.zPosition, i, plane);
          }
        }
      }

      // populate LogicalChannel data

      for (int i=0; i<seriesCount; i++) {
        setSeries(i);
        for (int q=0; q<getEffectiveSizeC(); q++) {
          if (q < channelNames.size()) {
            store.setChannelName(channelNames.get(q), i, q);
          }
          if (q < emWaves.size()) {
            int wave = emWaves.get(q).intValue();
            if (wave > 0) {
              store.setChannelEmissionWavelength(
                new PositiveInteger(wave), i, q);
            }
          }
          if (q < exWaves.size()) {
            int wave = exWaves.get(q).intValue();
            if (wave > 0) {
              store.setChannelExcitationWavelength(
                new PositiveInteger(wave), i, q);
            }
          }
        }
      }
      setSeries(0);

      // populate Plate data

      String rowNaming =
        Character.isDigit(rowName.charAt(0)) ? "Number" : "Letter";
      String colNaming =
        Character.isDigit(colName.charAt(0)) ? "Number" : "Letter";

      String plateName = currentId;
      int begin = plateName.lastIndexOf(File.separator) + 1;
      int end = plateName.lastIndexOf(".");
      plateName = plateName.substring(begin, end);

      store.setPlateID(MetadataTools.createLSID("Plate", 0), 0);
      store.setPlateName(plateName, 0);
      store.setPlateRowNamingConvention(getNamingConvention(rowNaming), 0);
      store.setPlateColumnNamingConvention(getNamingConvention(colNaming), 0);
      store.setPlateWellOriginX(0.5, 0);
      store.setPlateWellOriginY(0.5, 0);

      // populate Well data

      for (int i=0; i<seriesCount; i++) {
        int well = getWellFromSeries(i);
        int field = getFieldFromSeries(i);
        int totalTimepoints =
          oneTimepointPerSeries ? channelsPerTimepoint.size() : 1;
        int timepoint = i % totalTimepoints;

        int sampleIndex = field * totalTimepoints + timepoint;

        String imageID = MetadataTools.createLSID("Image", i);
        store.setWellSampleIndex(
          new NonNegativeInteger(i), 0, well, sampleIndex);
        store.setWellSampleImageRef(imageID, 0, well, sampleIndex);
        store.setWellSamplePositionX(posX.get(field), 0, well, sampleIndex);
        store.setWellSamplePositionY(posY.get(field), 0, well, sampleIndex);
      }
    }
  }

  // -- Helper methods --

  private int getFieldFromSeries(int series) {
    if (oneTimepointPerSeries) series /= channelsPerTimepoint.size();
    return series % fieldCount;
  }

  private int getWellFromSeries(int series) {
    if (oneTimepointPerSeries) series /= channelsPerTimepoint.size();
    int well = series / fieldCount;

    int counter = -1;

    for (int row=0; row<plateMap.length; row++) {
      for (int col=0; col<plateMap[row].length; col++) {
        if (plateMap[row][col]) {
          counter++;
        }
        if (counter == well) {
          return row * wellCols + col;
        }
      }
    }
    return -1;
  }

  // -- Helper classes --

  class MinimalInCellHandler extends DefaultHandler {
    private String currentImageFile;
    private String currentThumbnail;
    private int wellRow, wellCol;
    private int nChannels = 0;

    public void endElement(String uri, String localName, String qName) {
      if (qName.equals("PlateMap")) {
        int sizeT = getSizeT();
        if (sizeT == 0) {
          // There has been no <TimeSchedule> in the <PlateMap> defined to
          // populate channelsPerTimepoint so we have to assume that there is
          // only one timepoint otherwise the imageFiles array below will not
          // be correctly initialized.
          sizeT = 1;
        }
        if (channelsPerTimepoint.size() == 0) {
          // There has been no <TimeSchedule> in the <PlateMap> defined to
          // populate channelsPerTimepoint so we have to assume that all
          // channels are being acquired.
          channelsPerTimepoint.add(core[0].sizeC);
        }
        imageFiles = new Image[wellRows * wellCols][fieldCount][sizeT][];
        for (int well=0; well<wellRows*wellCols; well++) {
          for (int field=0; field<fieldCount; field++) {
            for (int t=0; t<sizeT; t++) {
              int channels = channelsPerTimepoint.get(t).intValue();
              imageFiles[well][field][t] = new Image[channels * getSizeZ()];
            }
          }
        }
      }
      else if (qName.equals("TimePoint")) {
        channelsPerTimepoint.add(new Integer(nChannels));
        nChannels = 0;
      }
      else if (qName.equals("Times")) {
        if (channelsPerTimepoint.size() == 0) {
          channelsPerTimepoint.add(new Integer(getSizeC()));
        }
        for (int i=0; i<channelsPerTimepoint.size(); i++) {
          int c = channelsPerTimepoint.get(i).intValue();
          if (c == 0) {
            channelsPerTimepoint.setElementAt(new Integer(getSizeC()), i);
          }
        }
      }
    }

    public void startElement(String uri, String localName, String qName,
      Attributes attributes)
    {
      if (qName.equals("Plate")) {
        wellRows = Integer.parseInt(attributes.getValue("rows"));
        wellCols = Integer.parseInt(attributes.getValue("columns"));
        plateMap = new boolean[wellRows][wellCols];
      }
      else if (qName.equals("Exclude")) {
        if (exclude == null) exclude = new boolean[wellRows][wellCols];
        int row = Integer.parseInt(attributes.getValue("row")) - 1;
        int col = Integer.parseInt(attributes.getValue("col")) - 1;
        exclude[row][col] = true;
      }
      else if (qName.equals("Images")) {
        totalImages = Integer.parseInt(attributes.getValue("number"));
      }
      else if (qName.equals("Image")) {
        String file = attributes.getValue("filename");
        String thumb = attributes.getValue("thumbnail");
        Location current = new Location(currentId).getAbsoluteFile();

        Location imageFile = new Location(current.getParentFile(), file);
        currentImageFile = imageFile.getAbsolutePath();
        currentThumbnail =
          new Location(current.getParentFile(), thumb).getAbsolutePath();
      }
      else if (qName.equals("Identifier")) {
        int field = Integer.parseInt(attributes.getValue("field_index"));
        int z = Integer.parseInt(attributes.getValue("z_index"));
        int c = Integer.parseInt(attributes.getValue("wave_index"));
        int t = Integer.parseInt(attributes.getValue("time_index"));
        int channels = channelsPerTimepoint.get(t).intValue();

        int index = FormatTools.getIndex("XYZCT", getSizeZ(),
          channels, 1, getSizeZ() * channels, z, c, 0);

        Image img = new Image();
        img.thumbnailFile = currentThumbnail;
        Location file = new Location(currentImageFile);
        img.filename = file.exists() ? currentImageFile : null;
        if (img.filename == null) {
          LOGGER.warn("{} does not exist.", currentImageFile);
        }
        currentImageFile = currentImageFile.toLowerCase();
        img.isTiff = currentImageFile.endsWith(".tif") ||
          currentImageFile.endsWith(".tiff");
        imageFiles[wellRow * wellCols + wellCol][field][t][index] = img;
      }
      else if (qName.equals("offset_point")) {
        fieldCount++;
      }
      else if (qName.equals("TimePoint")) {
        core[0].sizeT++;
      }
      else if (qName.equals("Wavelength")) {
        String fusion = attributes.getValue("fusion_wave");
        if (fusion.equals("false")) core[0].sizeC++;
      }
      else if (qName.equals("AcqWave")) {
        nChannels++;
      }
      else if (qName.equals("ZDimensionParameters")) {
        String nz = attributes.getValue("number_of_slices");
        if (nz != null) {
          core[0].sizeZ = Integer.parseInt(nz);
        }
        else core[0].sizeZ = 1;
      }
      else if (qName.equals("Row")) {
        wellRow = Integer.parseInt(attributes.getValue("number")) - 1;
      }
      else if (qName.equals("Column")) {
        wellCol = Integer.parseInt(attributes.getValue("number")) - 1;
        plateMap[wellRow][wellCol] = true;
      }
      else if (qName.equals("Size")) {
        imageWidth = Integer.parseInt(attributes.getValue("width"));
        imageHeight = Integer.parseInt(attributes.getValue("height"));
      }
      else if (qName.equals("NamingRows")) {
        rowName = attributes.getValue("begin");
      }
      else if (qName.equals("NamingColumns")) {
        colName = attributes.getValue("begin");
      }
    }
  }

  /** SAX handler for parsing XML. */
  class InCellHandler extends DefaultHandler {
    private String currentQName;
    private boolean openImage;
    private int nextEmWave = 0;
    private int nextExWave = 0;
    private MetadataStore store;
    private int nextPlate = 0;
    private int currentRow = -1, currentCol = -1;
    private int currentField = 0;
    private int currentImage, currentPlane;
    private Double timestamp, exposure, zPosition;
    private String channelName = null;

    public InCellHandler(MetadataStore store) {
      this.store = store;
    }

    public void characters(char[] ch, int start, int length) {
      String value = new String(ch, start, length);
      if (currentQName.equals("UserComment")) {
        store.setImageDescription(value, 0);
      }
    }

    public void endElement(String uri, String localName, String qName) {
      if (qName.equals("Image")) {
        wellCoordinates.put(new Integer(currentField),
          new int[] {currentRow, currentCol});
        openImage = false;

        int well = currentRow * wellCols + currentCol;
        Image img = imageFiles[well][currentField][currentImage][currentPlane];
        if (img != null) {
          img.deltaT = timestamp;
          img.exposure = exposure;
        }
      }
      else if (qName.equals("Wavelength")) {
        channelName = null;
      }
    }

    public void startElement(String uri, String localName, String qName,
      Attributes attributes)
    {
      currentQName = qName;
      for (int i=0; i<attributes.getLength(); i++) {
        addGlobalMeta(qName + " - " + attributes.getQName(i),
          attributes.getValue(i));
      }

      if (qName.equals("Microscopy")) {
        String experimentID = MetadataTools.createLSID("Experiment", 0);
        store.setExperimentID(experimentID, 0);
        try {
          store.setExperimentType(
            getExperimentType(attributes.getValue("type")), 0);
        }
        catch (FormatException e) {
          LOGGER.warn("", e);
        }
      }
      else if (qName.equals("Image")) {
        openImage = true;
        double time =
          Double.parseDouble(attributes.getValue("acquisition_time_ms"));
        timestamp = new Double(time / 1000);
      }
      else if (qName.equals("Identifier")) {
        currentField = Integer.parseInt(attributes.getValue("field_index"));
        int z = Integer.parseInt(attributes.getValue("z_index"));
        int c = Integer.parseInt(attributes.getValue("wave_index"));
        int t = Integer.parseInt(attributes.getValue("time_index"));
        currentImage = t;
        currentPlane = z * getSizeC() + c;
        int well = currentRow * wellCols + currentCol;
        Image img = imageFiles[well][currentField][currentImage][currentPlane];
        img.zPosition = zPosition;
      }
      else if (qName.equals("FocusPosition")) {
        zPosition = new Double(attributes.getValue("z"));
      }
      else if (qName.equals("Creation")) {
        String date = attributes.getValue("date"); // yyyy-mm-dd
        String time = attributes.getValue("time"); // hh:mm:ss
        creationDate = date + "T" + time;
      }
      else if (qName.equals("ObjectiveCalibration")) {
        store.setObjectiveNominalMagnification(new PositiveInteger((int)
          Double.parseDouble(attributes.getValue("magnification"))), 0, 0);
        store.setObjectiveLensNA(new Double(
          attributes.getValue("numerical_aperture")), 0, 0);
        try {
         store.setObjectiveImmersion(getImmersion("Other"), 0, 0);
        }
        catch (FormatException e) {
          LOGGER.warn("", e);
        }

        String objective = attributes.getValue("objective_name");
        String[] tokens = objective.split("_");

        store.setObjectiveManufacturer(tokens[0], 0, 0);
        String correction = tokens.length > 2 ? tokens[2] : "Other";
        try {
          store.setObjectiveCorrection(getCorrection(correction), 0, 0);
        }
        catch (FormatException e) {
          LOGGER.warn("", e);
        }

        Double pixelSizeX = new Double(attributes.getValue("pixel_width"));
        Double pixelSizeY = new Double(attributes.getValue("pixel_height"));
        Double refractive = new Double(attributes.getValue("refractive_index"));

        // link Objective to Image
        String objectiveID = MetadataTools.createLSID("Objective", 0, 0);
        store.setObjectiveID(objectiveID, 0, 0);
        for (int i=0; i<getSeriesCount(); i++) {
          store.setImageObjectiveSettingsID(objectiveID, i);
          store.setImageObjectiveSettingsRefractiveIndex(refractive, i);
          store.setPixelsPhysicalSizeX(pixelSizeX, i);
          store.setPixelsPhysicalSizeY(pixelSizeY, i);
        }
      }
      else if (qName.equals("ExcitationFilter")) {
        String wave = attributes.getValue("wavelength");
        if (wave != null) exWaves.add(new Integer(wave));
        channelName = attributes.getValue("name");
      }
      else if (qName.equals("EmissionFilter")) {
        String wave = attributes.getValue("wavelength");
        if (wave != null) emWaves.add(new Integer(wave));
        channelNames.add(attributes.getValue("name"));
      }
      else if (qName.equals("Camera")) {
        store.setDetectorModel(attributes.getValue("name"), 0, 0);
        try {
          store.setDetectorType(getDetectorType("Other"), 0, 0);
        }
        catch (FormatException e) {
          LOGGER.warn("", e);
        }
        String detectorID = MetadataTools.createLSID("Detector", 0, 0);
        store.setDetectorID(detectorID, 0, 0);
        for (int i=0; i<getSeriesCount(); i++) {
          setSeries(i);
          for (int q=0; q<getSizeC(); q++) {
            store.setDetectorSettingsID(detectorID, i, q);
          }
        }
        setSeries(0);
      }
      else if (qName.equals("Binning")) {
        String binning = attributes.getValue("value");
        for (int i=0; i<getSeriesCount(); i++) {
          setSeries(i);
          for (int q=0; q<getSizeC(); q++) {
            try {
              store.setDetectorSettingsBinning(getBinning(binning), i, q);
            }
            catch (FormatException e) { }
          }
        }
        setSeries(0);
      }
      else if (qName.equals("Gain")) {
        String value = attributes.getValue("value");
        if (value == null) {
          return;
        }
        Double gain = new Double(value);
        for (int i=0; i<getSeriesCount(); i++) {
          setSeries(i);
          for (int q=0; q<getSizeC(); q++) {
            store.setDetectorSettingsGain(gain, i, q);
          }
        }
        setSeries(0);
      }
      else if (qName.equals("PlateTemperature")) {
        Double temperature = new Double(attributes.getValue("value"));
        for (int i=0; i<getSeriesCount(); i++) {
          store.setImagingEnvironmentTemperature(temperature, i);
        }
      }
      else if (qName.equals("Plate")) {
        for (int r=0; r<wellRows; r++) {
          for (int c=0; c<wellCols; c++) {
            int well = r * wellCols + c;
            String wellID = MetadataTools.createLSID("Well", nextPlate, well);
            store.setWellID(wellID, nextPlate, well);
            store.setWellRow(new NonNegativeInteger(r), nextPlate, well);
            store.setWellColumn(new NonNegativeInteger(c), nextPlate, well);
          }
        }
        nextPlate++;
      }
      else if (qName.equals("Row")) {
        currentRow = Integer.parseInt(attributes.getValue("number")) - 1;
      }
      else if (qName.equals("Column")) {
        currentCol = Integer.parseInt(attributes.getValue("number")) - 1;
      }
      else if (qName.equals("Exposure") && openImage) {
        double exp = Double.parseDouble(attributes.getValue("time"));
        exposure = new Double(exp / 1000);
      }
      else if (qName.equals("offset_point")) {
        String x = attributes.getValue("x");
        String y = attributes.getValue("y");

        posX.add(new Double(x));
        posY.add(new Double(y));

        addGlobalMeta("X position for position #" + posX.size(), x);
        addGlobalMeta("Y position for position #" + posY.size(), y);
      }
    }
  }

  class Image {
    public String filename;
    public String thumbnailFile;
    public boolean isTiff;
    public Double deltaT, exposure;
    public Double zPosition;
  }

}
