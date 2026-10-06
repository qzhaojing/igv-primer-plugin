/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.apache.log4j.Logger
 *  org.broad.igv.PreferenceManager
 *  org.broad.igv.feature.RegionOfInterest
 *  org.broad.igv.feature.genome.GenomeManager
 *  org.broad.igv.lists.GeneList
 *  org.broad.igv.session.IGVSessionReader
 *  org.broad.igv.session.IGVSessionReader$SessionAttribute
 *  org.broad.igv.session.IGVSessionReader$SessionElement
 *  org.broad.igv.session.Session
 *  org.broad.igv.track.AbstractTrack
 *  org.broad.igv.track.AttributeManager
 *  org.broad.igv.track.DataSourceTrack
 *  org.broad.igv.track.FeatureTrack
 *  org.broad.igv.track.Track
 *  org.broad.igv.ui.IGV
 *  org.broad.igv.ui.TrackFilter
 *  org.broad.igv.ui.TrackFilterElement
 *  org.broad.igv.ui.panel.FrameManager
 *  org.broad.igv.ui.panel.ReferenceFrame
 *  org.broad.igv.ui.panel.TrackPanel
 *  org.broad.igv.util.FileUtils
 *  org.broad.igv.util.ResourceLocator
 *  org.broad.igv.util.Utilities
 */
package org.broad.igv.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.log4j.Logger;
import org.broad.igv.PreferenceManager;
import org.broad.igv.feature.RegionOfInterest;
import org.broad.igv.feature.genome.GenomeManager;
import org.broad.igv.lists.GeneList;
import org.broad.igv.session.IGVSessionReader;
import org.broad.igv.session.Session;
import org.broad.igv.track.AbstractTrack;
import org.broad.igv.track.AttributeManager;
import org.broad.igv.track.DataSourceTrack;
import org.broad.igv.track.FeatureTrack;
import org.broad.igv.track.Track;
import org.broad.igv.ui.IGV;
import org.broad.igv.ui.TrackFilter;
import org.broad.igv.ui.TrackFilterElement;
import org.broad.igv.ui.panel.FrameManager;
import org.broad.igv.ui.panel.ReferenceFrame;
import org.broad.igv.ui.panel.TrackPanel;
import org.broad.igv.util.FileUtils;
import org.broad.igv.util.ResourceLocator;
import org.broad.igv.util.Utilities;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class SessionWriter {
    static Logger log = Logger.getLogger(SessionWriter.class);
    Session session;
    private static int CURRENT_VERSION = 8;
    private static final String TRACK_TAG = IGVSessionReader.SessionElement.TRACK.getText();

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void saveSession(Session session, File outputFile) throws IOException {
        RuntimeException e;
        if (session == null) {
            e = new RuntimeException("No session found to save!");
            log.error((Object)"Session Management Error", (Throwable)e);
        }
        this.session = session;
        if (outputFile == null) {
            e = new RuntimeException("Can't save session file: " + outputFile);
            log.error((Object)"Session Management Error", (Throwable)e);
        }
        String xmlString = this.createXmlFromSession(session, outputFile);
        OutputStreamWriter fileWriter = null;
        try {
            fileWriter = new OutputStreamWriter(new FileOutputStream(outputFile), "UTF-8");
            fileWriter.write(xmlString);
        }
        finally {
            if (fileWriter != null) {
                fileWriter.close();
            }
        }
    }

    public String createXmlFromSession(Session session, File outputFile) throws RuntimeException {
        String xmlString = null;
        try {
            int nextAutoscaleGroup;
            String groupBy;
            String locus;
            DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document document = documentBuilder.newDocument();
            document.setStrictErrorChecking(true);
            Element globalElement = document.createElement(IGVSessionReader.SessionElement.SESSION.getText());
            globalElement.setAttribute(IGVSessionReader.SessionAttribute.VERSION.getText(), String.valueOf(CURRENT_VERSION));
            String genomeId = GenomeManager.getInstance().getGenomeId();
            if (genomeId != null) {
                globalElement.setAttribute(IGVSessionReader.SessionAttribute.GENOME.getText(), genomeId);
            }
            if ((locus = session.getLocusString()) != null && !FrameManager.isGeneListMode()) {
                globalElement.setAttribute(IGVSessionReader.SessionAttribute.LOCUS.getText(), locus);
            }
            if ((groupBy = IGV.getInstance().getGroupByAttribute()) != null) {
                globalElement.setAttribute(IGVSessionReader.SessionAttribute.GROUP_TRACKS_BY.getText(), groupBy);
            }
            if ((nextAutoscaleGroup = session.getNextAutoscaleGroup()) > 1) {
                globalElement.setAttribute(IGVSessionReader.SessionAttribute.NEXT_AUTOSCALE_GROUP.getText(), String.valueOf(nextAutoscaleGroup));
            }
            if (session.isRemoveEmptyPanels()) {
                globalElement.setAttribute("removeEmptyTracks", "true");
            }
            globalElement.setAttribute(IGVSessionReader.SessionAttribute.HAS_GENE_TRACK.getText(), "" + IGV.getInstance().hasGeneTrack());
            globalElement.setAttribute(IGVSessionReader.SessionAttribute.HAS_SEQ_TRACK.getText(), "" + IGV.getInstance().hasSequenceTrack());
            globalElement.setAttribute("path", outputFile.getAbsolutePath());
            this.writeResources(outputFile, globalElement, document);
            this.writePanels(globalElement, document);
            this.writePanelLayout(globalElement, document);
            this.writeRegionsOfInterest(globalElement, document);
            this.writeFilters(session, globalElement, document);
            if (FrameManager.isGeneListMode()) {
                this.writeGeneList(globalElement, document);
            }
            if (session.getHiddenAttributes() != null && session.getHiddenAttributes().size() > 0) {
                this.writeHiddenAttributes(session, globalElement, document);
            }
            // v0.1.47（插件补丁）：把当前引物轨（分组/配对连线/布局行/颜色）写进 session
            try { org.broad.igv.primer.PrimerSession.writeTo(document, globalElement); } catch (Throwable ignore) { }
            document.appendChild(globalElement);
            xmlString = Utilities.getString((Document)document);
        }
        catch (Exception e) {
            String message = "An error has occurred while trying to create the session!";
            log.error((Object)message, (Throwable)e);
            JOptionPane.showMessageDialog(IGV.getMainFrame(), message);
            throw new RuntimeException(e);
        }
        return xmlString;
    }

    private void writeFilters(Session session, Element globalElement, Document document) {
        TrackFilter trackFilter = session.getFilter();
        if (trackFilter != null) {
            Element filter = document.createElement(IGVSessionReader.SessionElement.FILTER.getText());
            filter.setAttribute(IGVSessionReader.SessionAttribute.NAME.getText(), trackFilter.getName());
            if (IGV.getInstance().isFilterMatchAll()) {
                filter.setAttribute(IGVSessionReader.SessionAttribute.FILTER_MATCH.getText(), "all");
            } else if (!IGV.getInstance().isFilterMatchAll()) {
                filter.setAttribute(IGVSessionReader.SessionAttribute.FILTER_MATCH.getText(), "any");
            } else {
                filter.setAttribute(IGVSessionReader.SessionAttribute.FILTER_MATCH.getText(), "all");
            }
            if (IGV.getInstance().isFilterShowAllTracks()) {
                filter.setAttribute(IGVSessionReader.SessionAttribute.FILTER_SHOW_ALL_TRACKS.getText(), "true");
            } else {
                filter.setAttribute(IGVSessionReader.SessionAttribute.FILTER_SHOW_ALL_TRACKS.getText(), "false");
            }
            globalElement.appendChild(filter);
            Iterator iterator = session.getFilter().getFilterElements();
            while (iterator.hasNext()) {
                TrackFilterElement trackFilterElement = (TrackFilterElement)iterator.next();
                Element filterElementElement = document.createElement(IGVSessionReader.SessionElement.FILTER_ELEMENT.getText());
                filterElementElement.setAttribute(IGVSessionReader.SessionAttribute.ITEM.getText(), trackFilterElement.getSelectedItem());
                filterElementElement.setAttribute(IGVSessionReader.SessionAttribute.OPERATOR.getText(), trackFilterElement.getComparisonOperator().getValue());
                filterElementElement.setAttribute(IGVSessionReader.SessionAttribute.VALUE.getText(), trackFilterElement.getValue());
                filterElementElement.setAttribute(IGVSessionReader.SessionAttribute.BOOLEAN_OPERATOR.getText(), trackFilterElement.getBooleanOperator().getValue());
                filter.appendChild(filterElementElement);
            }
        }
    }

    private void writeRegionsOfInterest(Element globalElement, Document document) {
        Collection<RegionOfInterest> regions = this.session.getAllRegionsOfInterest();
        if (regions != null && !regions.isEmpty()) {
            Element regionsElement = document.createElement(IGVSessionReader.SessionElement.REGIONS.getText());
            for (RegionOfInterest region : regions) {
                Element regionElement = document.createElement(IGVSessionReader.SessionElement.REGION.getText());
                regionElement.setAttribute(IGVSessionReader.SessionAttribute.CHROMOSOME.getText(), region.getChr());
                regionElement.setAttribute(IGVSessionReader.SessionAttribute.START_INDEX.getText(), String.valueOf(region.getStart()));
                regionElement.setAttribute(IGVSessionReader.SessionAttribute.END_INDEX.getText(), String.valueOf(region.getEnd()));
                if (region.getDescription() != null) {
                    regionElement.setAttribute(IGVSessionReader.SessionAttribute.DESCRIPTION.getText(), region.getDescription());
                }
                regionsElement.appendChild(regionElement);
            }
            globalElement.appendChild(regionsElement);
        }
    }

    private void writeHiddenAttributes(Session session, Element globalElement, Document document) {
        Element hiddenAttributes = document.createElement(IGVSessionReader.SessionElement.HIDDEN_ATTRIBUTES.getText());
        for (String attribute : session.getHiddenAttributes()) {
            Element regionElement = document.createElement(IGVSessionReader.SessionElement.ATTRIBUTE.getText());
            regionElement.setAttribute(IGVSessionReader.SessionAttribute.NAME.getText(), attribute);
            hiddenAttributes.appendChild(regionElement);
        }
        globalElement.appendChild(hiddenAttributes);
    }

    private void writeGeneList(Element globalElement, Document document) {
        GeneList geneList = this.session.getCurrentGeneList();
        if (geneList != null) {
            Element geneListElement = document.createElement(IGVSessionReader.SessionElement.GENE_LIST.getText());
            geneListElement.setAttribute(IGVSessionReader.SessionAttribute.NAME.getText(), geneList.getName());
            StringBuffer genes = new StringBuffer();
            for (String gene : geneList.getLoci()) {
                genes.append(gene);
                genes.append("\n");
            }
            geneListElement.setTextContent(genes.toString());
            globalElement.appendChild(geneListElement);
            for (ReferenceFrame frame : FrameManager.getFrames()) {
                Element frameElement = document.createElement(IGVSessionReader.SessionElement.FRAME.getText());
                frameElement.setAttribute(IGVSessionReader.SessionAttribute.NAME.getText(), frame.getName());
                frameElement.setAttribute(IGVSessionReader.SessionAttribute.CHR.getText(), frame.getChrName());
                frameElement.setAttribute(IGVSessionReader.SessionAttribute.START.getText(), String.valueOf(frame.getOrigin()));
                frameElement.setAttribute(IGVSessionReader.SessionAttribute.END.getText(), String.valueOf(frame.getEnd()));
                geneListElement.appendChild(frameElement);
            }
        }
    }

    private void writeResources(File outputFile, Element globalElement, Document document) throws IOException {
        Collection<ResourceLocator> resourceLocators = this.getResourceLocatorSet();
        if (resourceLocators != null && !resourceLocators.isEmpty()) {
            Element filesElement = document.createElement(IGVSessionReader.SessionElement.RESOURCES.getText());
            for (ResourceLocator resourceLocator : resourceLocators) {
                if (!resourceLocator.exists() && resourceLocator.getPath() == null) continue;
                Element dataFileElement = document.createElement(IGVSessionReader.SessionElement.RESOURCE.getText());
                boolean useRelative = PreferenceManager.getInstance().getAsBoolean("SESSION.RELATIVE_PATH");
                String relativePath = useRelative ? FileUtils.getRelativePath((String)outputFile.getAbsolutePath(), (String)resourceLocator.getPath()) : resourceLocator.getPath();
                dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.PATH.getText(), relativePath);
                if (resourceLocator.getName() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.NAME.getText(), resourceLocator.getName());
                }
                if (resourceLocator.getDBUrl() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.SERVER_URL.getText(), resourceLocator.getDBUrl());
                }
                if (resourceLocator.getTrackInfoURL() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.HYPERLINK.getText(), resourceLocator.getTrackInfoURL());
                }
                if (resourceLocator.getFeatureInfoURL() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.FEATURE_URL.getText(), resourceLocator.getFeatureInfoURL());
                }
                if (resourceLocator.getDescription() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.DESCRIPTION.getText(), resourceLocator.getDescription());
                }
                if (resourceLocator.getType() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.TYPE.getText(), resourceLocator.getType());
                }
                if (resourceLocator.getIndexPath() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.INDEX.getText(), resourceLocator.getIndexPath());
                }
                if (resourceLocator.getCoverage() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.COVERAGE.getText(), resourceLocator.getCoverage());
                }
                if (resourceLocator.getTrackLine() != null) {
                    dataFileElement.setAttribute(IGVSessionReader.SessionAttribute.TRACK_LINE.getText(), resourceLocator.getTrackLine());
                }
                filesElement.appendChild(dataFileElement);
            }
            globalElement.appendChild(filesElement);
        }
    }

    private void writePanels(Element globalElement, Document document) throws DOMException {
        for (TrackPanel trackPanel : IGV.getInstance().getTrackPanels()) {
            List<Track> tracks = trackPanel.getTracks();
            if (tracks == null || tracks.isEmpty()) continue;
            Element panelElement = document.createElement(IGVSessionReader.SessionElement.PANEL.getText());
            panelElement.setAttribute("name", trackPanel.getName());
            panelElement.setAttribute("height", String.valueOf(trackPanel.getHeight()));
            panelElement.setAttribute("width", String.valueOf(trackPanel.getWidth()));
            Element tmpTrackParent = document.createElement("dummy");
            try {
                Marshaller m = IGVSessionReader.getJAXBContext().createMarshaller();
                m.setProperty("jaxb.fragment", true);
                for (Track track : tracks) {
                    // v0.1.47（插件补丁）：引物轨不走 JAXB 通用 Track（回来时没有数据源会丢），
                    // 改由 PrimerSession 写 <PrimerTracks> 块；原版 IGV 不识别该元素 → 忽略，不报错不显示。
                    if (track instanceof org.broad.igv.primer.PrimerTrack) continue;
                    SessionWriter.marshalTrack(m, track, tmpTrackParent, track.getClass());
                    Element trackElement = (Element)tmpTrackParent.getChildNodes().item(0);
                    for (Map.Entry attrValue : track.getPersistentState().entrySet()) {
                        trackElement.setAttribute((String)attrValue.getKey(), (String)attrValue.getValue());
                    }
                    this.marshalTrackChildren(m, track, trackElement);
                    panelElement.appendChild(trackElement);
                }
            }
            catch (JAXBException e) {
                throw new RuntimeException(e);
            }
            globalElement.appendChild(panelElement);
        }
    }

    private static void marshalTrack(Marshaller m, Track track, Node trackParent, Class marshalClass) throws JAXBException {
        if (marshalClass == null || marshalClass.equals(Object.class)) {
            throw new JAXBException(track.getClass() + " and none of its superclasses are known");
        }
        if (AbstractTrack.knownUnknownTrackClasses.contains(marshalClass)) {
            SessionWriter.marshalTrack(m, track, trackParent, marshalClass.getSuperclass());
            return;
        }
        try {
            JAXBElement<Track> el = new JAXBElement<Track>(new QName("", TRACK_TAG), marshalClass, track);
            m.marshal(el, trackParent);
        }
        catch (JAXBException e) {
            AbstractTrack.knownUnknownTrackClasses.add(marshalClass);
            SessionWriter.marshalTrack(m, track, trackParent, marshalClass.getSuperclass());
        }
    }

    private void marshalTrackChildren(Marshaller m, Track track, Element trackElement) throws JAXBException {
        if (track instanceof FeatureTrack) {
            FeatureTrack featureTrack = (FeatureTrack)track;
            featureTrack.marshalSource(m, trackElement);
        } else if (track instanceof DataSourceTrack) {
            DataSourceTrack dataSourceTrack = (DataSourceTrack)track;
            dataSourceTrack.marshalSource(m, trackElement);
        }
    }

    private void writePanelLayout(Element globalElement, Document document) {
        double[] dividerFractions = IGV.getInstance().getMainPanel().getDividerFractions();
        if (dividerFractions.length > 0) {
            Element panelLayout = document.createElement(IGVSessionReader.SessionElement.PANEL_LAYOUT.getText());
            globalElement.appendChild(panelLayout);
            StringBuffer locString = new StringBuffer();
            locString.append(String.valueOf(dividerFractions[0]));
            for (int i = 1; i < dividerFractions.length; ++i) {
                locString.append("," + dividerFractions[i]);
            }
            panelLayout.setAttribute("dividerFractions", locString.toString());
        }
    }

    public Collection<ResourceLocator> getResourceLocatorSet() {
        Set<ResourceLocator> loadedAttributeResources;
        ArrayList<ResourceLocator> locators = new ArrayList<ResourceLocator>();
        Set<ResourceLocator> currentTrackFileLocators = IGV.getInstance().getDataResourceLocators();
        if (currentTrackFileLocators != null) {
            for (ResourceLocator locator : currentTrackFileLocators) {
                locators.add(locator);
            }
        }
        if ((loadedAttributeResources = AttributeManager.getInstance().getLoadedResources()) != null) {
            for (ResourceLocator attributeLocator : loadedAttributeResources) {
                locators.add(attributeLocator);
            }
        }
        return locators;
    }
}
