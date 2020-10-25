package org.opentripplanner.netex;

import org.opentripplanner.datastore.CompositeDataSource;
import org.opentripplanner.datastore.DataSource;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.model.impl.OtpTransitServiceBuilder;
import org.opentripplanner.netex.loader.GroupEntries;
import org.opentripplanner.netex.loader.NetexDataSourceHierarchy;
import org.opentripplanner.netex.index.NetexEntityDataIndex;
import org.opentripplanner.netex.loader.NetexXmlParser;
import org.opentripplanner.netex.mapping.NetexMapper;
import org.opentripplanner.netex.loader.parser.NetexDocumentParser;
import org.opentripplanner.routing.trippattern.Deduplicator;
import org.opentripplanner.standalone.config.NetexConfig;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBException;
import java.io.Closeable;
import java.io.IOException;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * Loads/reads a NeTEx bundle of a data source(zip file/directory/cloud storage) and maps it into
 * the OTP internal transit model.
 * <p>
 * The NeTEx loader will use a file naming convention to load files in a particular order and
 * keeping an index of entities to enable linking. The convention is documented here
 *{@link NetexConfig#sharedFilePattern} and here
 * {@link NetexDataSourceHierarchy}.
 * <p>
 * This class is also responsible for logging progress and exception handling.
 */
public class NetexBundle implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(NetexModule.class);

    /** stack of NeTEx elements needed to link the input to existing data */
    private final Deque<NetexEntityDataIndex> netexIndex = new LinkedList<>();

    private final CompositeDataSource source;

    private final NetexDataSourceHierarchy hierarchy;

    /** maps the NeTEx XML document to OTP transit model. */
    private NetexMapper otpMapper;

    private NetexXmlParser xmlParser;

    private final String netexFeedId;


    public NetexBundle(
            String netexFeedId,
            CompositeDataSource source,
            NetexDataSourceHierarchy hierarchy

    ) {
        this.netexFeedId = netexFeedId;
        this.source = source;
        this.hierarchy = hierarchy;
    }

    /** load the bundle, map it to the OTP transit model and return */
    public OtpTransitServiceBuilder loadBundle(
            Deduplicator deduplicator,
            DataImportIssueStore issueStore
    ) {
        LOG.info("Reading {}", hierarchy.description());

        // Store result in a mutable OTP Transit Model
        OtpTransitServiceBuilder transitBuilder = new OtpTransitServiceBuilder();

        // init parser and mapper
        xmlParser = new NetexXmlParser();
        otpMapper = new NetexMapper(transitBuilder, netexFeedId, deduplicator, issueStore);

        // Load data
        loadFileEntries();

        return transitBuilder;
    }

    public void checkInputs() {
        if (!source.exists()) {
            throw new RuntimeException("NeTEx " + source.path() + " does not exist.");
        }
    }


    /* private methods */

    /** Load all files entries in the bundle */
    private void loadFileEntries() {

        // Add a global(this zip file) shared NeTEX DAO
        netexIndex.addFirst(new NetexEntityDataIndex());

        // Load global shared files
        loadFilesThenMapToOtpTransitModel("shared file", hierarchy.sharedEntries());

        for (GroupEntries group : hierarchy.groups()) {
            LOG.info("reading group {}", group.name());

            scopeInputData(() -> {
                // Load shared group files
                loadFilesThenMapToOtpTransitModel(
                        "shared group file",
                        group.sharedEntries()
                );

                for (DataSource entry : group.independentEntries()) {
                    scopeInputData(() -> {
                        // Load each independent file in group
                        loadFilesThenMapToOtpTransitModel("group file", List.of(entry));
                    });
                }
            });
        }
    }

    /**
     * make a new index and pushes it on the index stack, before executing the task and
     * at the end pop of the index.
     */
    private void scopeInputData(Runnable task) {
        netexIndex.addFirst(new NetexEntityDataIndex(index()));
        task.run();
        netexIndex.removeFirst();
    }

    /**
     * Load a set of files and map the entries to OTP Transit model after the loading is
     * complete. It is important to do this in 2 steps to be able to link references.
     * An attempt to map each entry, when read, would lead to missing references, since
     * the order entries are read is not enforced in any way.
     */
    private void loadFilesThenMapToOtpTransitModel(String fileDescription, Iterable<DataSource> entries) {
        for (DataSource entry : entries) {
            // Load entry and store it in the index
            loadSingeFileEntry(fileDescription, entry);
        }
        // map current NeTEx objects into the OTP Transit Model
        otpMapper.mapNetexToOtp(index().readOnlyView());
    }

    private NetexEntityDataIndex index() {
        return netexIndex.peekFirst();
    }

    /** Load a single entry and store it in the index for later */
    private void loadSingeFileEntry(String fileDescription, DataSource entry) {
        try {
            LOG.info("reading entity {}: {}", fileDescription, entry.name());

            PublicationDeliveryStructure doc = xmlParser.parseXmlDoc(entry.asInputStream());
            NetexDocumentParser.parseAndPopulateIndex(index(), doc);

        } catch (JAXBException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
