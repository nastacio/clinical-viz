/********************************************************* {COPYRIGHT-TOP} ***
 * ctgov-viz
 *
 * Public domain
 * MIT License
 * 
 * https://github.com/nastacio/clinical-viz
 ********************************************************* {COPYRIGHT-END} **/
package com.sourcepatch.ctviz;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.gephi.graph.api.DirectedGraph;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Node;
import org.gephi.graph.api.Table;
import org.gephi.io.exporter.api.ExportController;
import org.gephi.layout.plugin.AutoLayout;
import org.gephi.layout.plugin.fruchterman.FruchtermanReingold;
import org.gephi.project.api.Project;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.openide.util.Lookup;

import com.google.maps.GeoApiContext;
import com.google.maps.GeocodingApi;
import com.google.maps.GeocodingApiRequest;
import com.google.maps.model.ComponentFilter;
import com.google.maps.model.GeocodingResult;
import com.google.maps.model.LatLng;
import com.sourcepatch.ctviz.ctgov.AddressStruct;
import com.sourcepatch.ctviz.ctgov.AgencyClassEnum;
import com.sourcepatch.ctviz.ctgov.ClinicalStudy;
import com.sourcepatch.ctviz.ctgov.EligibilityStruct;
import com.sourcepatch.ctviz.ctgov.FacilityStruct;
import com.sourcepatch.ctviz.ctgov.InterventionStruct;
import com.sourcepatch.ctviz.ctgov.InterventionTypeEnum;
import com.sourcepatch.ctviz.ctgov.SponsorsStruct;
import com.sourcepatch.ctviz.ctgov.StudyDesignInfoStruct;
import com.sourcepatch.ctviz.ctgov.StudyTypeEnum;

/**
 * Converts a clinical trial search into a graph visualization.
 * 
 * @author Denilson Nastacio
 */
public class App {

	private static final String NCT_DATE_PATTERN_1 = "MMMMM yyyy";
	private static final String NCT_DATE_PATTERN_2 = "MMMMM dd, yyyy";
	private static final SimpleDateFormat NCT_DATE_FORMAT_1 = new SimpleDateFormat(NCT_DATE_PATTERN_1);
	private static final SimpleDateFormat NCT_DATE_FORMAT_2 = new SimpleDateFormat(NCT_DATE_PATTERN_2);

	private Map<String, String> stateAbbrev = new TreeMap<>();
	private Map<String, String> cuiDisease = new TreeMap<>();
	private Map<String, String> diseaseCui = new TreeMap<>();
	private Map<String, String> nctConditionDisease = new TreeMap<>();
	private Map<String, LatLng> locationCoordMap = new TreeMap<>();

	/**
	 * Google GeoCode API key
	 * 
	 * @see https://developers.google.com/maps/documentation/geocoding/intro#geocoding
	 */
	private static final String PROPERTY_GOOGLE_MAPS_APIKEY = "google.maps.apikey";
	private static final GeoApiContext googleGeoContext = new GeoApiContext()
			.setApiKey(System.getProperty(PROPERTY_GOOGLE_MAPS_APIKEY));

	/*
	 * Ensuring default logging properties are loaded
	 */
	static {
		try {
			if (System.getProperty("java.util.logging.config.file") == null) {
				try (InputStream inputStream = App.class.getResourceAsStream("/logging.properties")) {
					LogManager.getLogManager().readConfiguration(inputStream);
				}
			} else {
				LogManager.getLogManager().readConfiguration();
			}
		} catch (final IOException e) {
			Logger.getAnonymousLogger().severe("Could not load default logging.properties file");
			Logger.getAnonymousLogger().severe(e.getMessage());
		}
	}

	/**
	 * Logging
	 */
	private static final Logger LOG = Logger.getLogger(App.class.getName());

	/*
	 * Public methods.
	 */

	/**
	 * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		App app = new App();

		app.init();

		String searchTerm = args[0];
		Workspace w = app.generateGraph(searchTerm);

		// See if graph is well imported
		GraphController graphController = Lookup.getDefault().lookup(GraphController.class);
		GraphModel gm = graphController.getGraphModel(w);
		DirectedGraph g = gm.getDirectedGraph();

		// Run Tasks and wait for termination in the current thread
		// createLayoutRunnable(gm);

		// Export
		Path outGraph = Paths.get("out/ctgraph.gexf");
		Files.createDirectories(outGraph.getParent());

		ExportController ec = Lookup.getDefault().lookup(ExportController.class);
		ec.exportFile(outGraph.toFile(), w);

		LOG.info("Output graph written to " + outGraph.toFile().getAbsolutePath());

		Path outConditionPhrases = Paths.get("out/ct.condition.phrases.txt");
		try (PrintWriter pw = new PrintWriter(outConditionPhrases.toFile())) {
			g.getNodes().toCollection().stream()
					.filter(n -> n.getAttribute(GraphSchema.VERTEX_PROPERTY_CONDITION_RAW) != null)
					.forEach(n -> pw.println(n.getAttribute(GraphSchema.VERTEX_PROPERTY_CONDITION_RAW) + " ."));
		}

		Path outInterventionPhrases = Paths.get("out/ct.intervention.phrases.txt");
		try (PrintWriter pw = new PrintWriter(outInterventionPhrases.toFile())) {
			g.getNodes().toCollection().stream()
					.filter(n -> n.getAttribute(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_NAME) != null)
					.forEach(n -> pw.println(n.getAttribute(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_NAME) + " ."));
		}

		LOG.info("Output conditions written to " + outConditionPhrases.toFile().getAbsolutePath());
	}

	/**
	 * 
	 * @throws Exception
	 */
	public void init() throws Exception {
		loadConditionMaps();
		loadStateAbbrebiationMap();
	}

	/**
	 * 
	 * @throws IOException
	 */
	public void loadConditionMaps() throws IOException {
		try (ZipFile zf = new ZipFile("src/main/resources/MRCONSO.conditions.zip")) {
			ZipEntry entry = zf.getEntry("dsyn.rrf");
			InputStream zis = zf.getInputStream(entry);
			BufferedReader br = new BufferedReader(new InputStreamReader(zis));

			String line = null;
			while ((line = br.readLine()) != null) {
				String[] tokens = line.split("\\|");
				String cui = tokens[0];
				String preferredName = tokens[14];

				cuiDisease.put(cui, preferredName);
				diseaseCui.put(preferredName, cui);
			}
		}
		LOG.info("Loaded condition maps. Unique concepts:" + cuiDisease.size() + " Unique surface forms:"
				+ diseaseCui.size());
	}

	/**
	 * 
	 * @throws IOException
	 */
	public void loadStateAbbrebiationMap() throws IOException {

		try (InputStream resourceAsStream = getClass().getResourceAsStream("/states.csv");
				InputStreamReader in = new InputStreamReader(resourceAsStream);
				BufferedReader br = new BufferedReader(in)) {

			String line = null;
			while ((line = br.readLine()) != null) {
				String[] tokens = line.split(",");
				String expanded = tokens[0];
				String abbrv = tokens[1];

				stateAbbrev.put(expanded, abbrv);
			}
		}
	}

	/**
	 * 
	 * @param searchTerm
	 * @return
	 * @throws Exception
	 */
	public Workspace generateGraph(String searchTerm) throws Exception {
		// Init a project - and therefore a workspace
		ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
		pc.newProject();
		Project p = pc.getProjects().getProjects()[0];

		Workspace result = pc.newWorkspace(p);

		GraphController graphController = Lookup.getDefault().lookup(GraphController.class);
		GraphModel gm = graphController.getGraphModel(result);

		addGraphMetadata(gm);

		Graph g = gm.getGraph();

		String instancePath = "com.sourcepatch.ctviz.ctgov";
		JAXBContext jc = JAXBContext.newInstance(instancePath);
		Unmarshaller u = jc.createUnmarshaller();

		String urlStr = "https://clinicaltrials.gov/ct2/results/download?down_stds=all&down_typ=results&down_flds=shown&down_fmt=plain&show_down=Y&term=";
		urlStr += searchTerm;
		URL url = new URL(urlStr);
		int trialCount = 0;
		try (InputStream is = url.openStream(); ZipInputStream zis = new ZipInputStream(is)) {

			int entries = 10;
			ZipEntry ctXmlEntry = null;
			while ((ctXmlEntry = zis.getNextEntry()) != null && entries-- >= 0) {

				LOG.info(ctXmlEntry.getName());

				Path tf = Files.createTempFile(GraphSchema.EDGE_PROPERTY_NCT_ID, "xml");
				Files.copy(zis, tf, StandardCopyOption.REPLACE_EXISTING);

				try (InputStream ftf = Files.newInputStream(tf)) {
					Object obj = u.unmarshal(ftf);
					ClinicalStudy study = (ClinicalStudy) obj;
					Files.delete(tf);

					addStudyToGraph(study, gm);
					trialCount++;
				}
			}
		}

		LOG.info("Processed " + trialCount + " clinical trials into graph. Nodes: " + g.getNodeCount() + ". Edges: "
				+ g.getEdgeCount());

		return result;
	}

	private void addGraphMetadata(GraphModel gm) {
		Table nodeTable = gm.getNodeTable();
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_LABEL_V, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_ADDRESS_CITY, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_ADDRESS_STATE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_ADDRESS_COUNTRY, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_ADDRESS_ZIP, String.class);

		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_LOCATION_FULL_ADDRESS, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_LOCATION_LATITUDE, Double.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_LOCATION_LONGITUDE, Double.class);

		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_CONDITION_NAME, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_CONDITION_RAW, String.class);

		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_STUDY_ID, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_ORG_STUDY_ID, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_TITLE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_OVERALL_STATUS, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_PHASE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_STUDY_TYPE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_ENROLLMENT, Long.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_GENDER, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_MIN_AGE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_MAX_AGE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_MASKING, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_PRIMARY_PURPOSE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_MODEL, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_TYPE, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_NAME, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_NCT_START_YEAR, Integer.class);

		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_SPONSOR_CLASS, String.class);
		nodeTable.addColumn(GraphSchema.VERTEX_PROPERTY_SPONSOR_NAME, String.class);

		Table edgeTable = gm.getEdgeTable();
		edgeTable.addColumn(GraphSchema.EDGE_PROPERTY_LABEL, String.class);
		edgeTable.addColumn(GraphSchema.EDGE_PROPERTY_LOCATION_NAME, String.class);
		edgeTable.addColumn(GraphSchema.EDGE_PROPERTY_NCT_ID, String.class);
		edgeTable.addColumn(GraphSchema.EDGE_PROPERTY_NCT_INTERVENTION_TYPE, String.class);
	}

	/*
	 * Private methods.
	 */

	/**
	 * 
	 * @param study
	 * @param g
	 */
	private void addStudyToGraph(ClinicalStudy study, GraphModel gm) {

		String nctId = study.getIdInfo().getNctId();

		//
		// Add nodes
		//

		String studyType = study.getStudyType() != null ? study.getStudyType().toString() : "";
		String briefTitle = getStringOrEmpty(study.getBriefTitle());
		String overallStatus = getStringOrEmpty(study.getOverallStatus());
		String phase = study.getPhase() != null ? study.getPhase().toString() : "";
		Long enrollment = study.getEnrollment() != null ? study.getEnrollment().getValue().longValue() : 0;
		String orgStudyId = getStringOrEmpty(study.getIdInfo().getOrgStudyId());
		EligibilityStruct studyEligibility = study.getEligibility();
		String genderStr = studyEligibility.getGender().toString();
		String minAge = studyEligibility.getMinimumAge();
		String maxAge = studyEligibility.getMaximumAge();
		Node ctVertex = gm.factory().newNode();

		ctVertex.setLabel(GraphSchema.VERTEX_LABEL_TRIAL);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_LABEL_V, GraphSchema.VERTEX_LABEL_TRIAL);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_STUDY_ID, nctId);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_ORG_STUDY_ID, orgStudyId);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_TITLE, briefTitle);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_OVERALL_STATUS, overallStatus);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_PHASE, phase);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_STUDY_TYPE, studyType);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_ENROLLMENT, enrollment);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_GENDER, genderStr);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_MIN_AGE, minAge);
		ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_MAX_AGE, maxAge);
		Graph g = gm.getDirectedGraph();
		g.addNode(ctVertex);

		StudyDesignInfoStruct designInfo = study.getStudyDesignInfo();
		if (designInfo != null) {
			String interventionModel = getStringOrEmpty(designInfo.getInterventionModel());
			String primaryPurpose = getStringOrEmpty(designInfo.getPrimaryPurpose());
			String masking = getStringOrEmpty(designInfo.getMasking());

			ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_MODEL, interventionModel);
			ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_PRIMARY_PURPOSE, primaryPurpose);
			ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_MASKING, masking);
		}

		if (study.getStudyType().equals(StudyTypeEnum.INTERVENTIONAL)) {
			List<InterventionStruct> interventions = study.getIntervention();
			for (InterventionStruct intv : interventions) {
				Node interventionVertex = getOrCreateIntervention(gm, intv);
				Edge testsEdge = gm.factory().newEdge(ctVertex, interventionVertex, true);
				testsEdge.setLabel(GraphSchema.EDGE_LABEL_TESTS);
				testsEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_TESTS);
				testsEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_INTERVENTION_TYPE,
						intv.getInterventionType().toString());
				g.addEdge(testsEdge);
			}
		}

		if (study.getStartDate() != null) {
			String startDateStr = study.getStartDate().getValue();
			try {
				int startYear = getYear(startDateStr);
				if (startYear < 1900) {
					LOG.warning(nctId + " has a likely invalid start year: " + startDateStr);
				} else {
					ctVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_START_YEAR, startYear);
				}
			} catch (ParseException e) {
				LOG.warning(nctId + " does not have a valid start year: " + startDateStr);
			}
		}

		SponsorsStruct ssList = study.getSponsors();
		String sponsorAgency = ssList.getLeadSponsor().getAgency();
		AgencyClassEnum agencyClass = ssList.getLeadSponsor().getAgencyClass();
		final Node sv = getOrCreateSponsorVertex(gm, sponsorAgency, agencyClass.toString());

		List<String> studyConditions = study.getCondition();

		// sponsor -> condition
		// sponsor -> trial
		studyConditions.forEach(c -> {
			Node conditionVertex = getOrCreateConditionVertex(gm, c);
			Edge researchesEdge = gm.factory().newEdge(sv, conditionVertex, true);
			researchesEdge.setLabel(GraphSchema.EDGE_LABEL_RESEARCHES);
			researchesEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_RESEARCHES);
			researchesEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_ID, nctId);
			g.addEdge(researchesEdge);

			Edge sponsorsEdge = gm.factory().newEdge(sv, ctVertex, true);
			sponsorsEdge.setLabel(GraphSchema.EDGE_LABEL_SPONSORS);
			sponsorsEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_SPONSORS);
			sponsorsEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_ID, nctId);
			g.addEdge(sponsorsEdge);
		});

		study.getSponsors().getCollaborator().forEach(collabAgency -> {
			// sponsor -> collaborators
			String collab = collabAgency.getAgency();

			AgencyClassEnum collabClass = collabAgency.getAgencyClass();
			Node collabVertex = getOrCreateSponsorVertex(gm, collab, collabClass.toString());

			Edge leadsEdge = gm.factory().newEdge(sv, collabVertex, true);
			leadsEdge.setLabel(GraphSchema.EDGE_LABEL_LEADS);
			leadsEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_LEADS);
			leadsEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_ID, nctId);
			g.addEdge(leadsEdge);

			Edge collabEdge = gm.factory().newEdge(collabVertex, sv, true);
			collabEdge.setLabel(GraphSchema.EDGE_LABEL_COLLABORATES);
			collabEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_COLLABORATES);
			collabEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_ID, nctId);
			g.addEdge(collabEdge);

			// collaborators -> condition
			// collaborators -> trial
			studyConditions.forEach(c -> {
				Node conditionVertex = getOrCreateConditionVertex(gm, c);

				Edge researchesEdge = gm.factory().newEdge(collabVertex, conditionVertex, true);
				researchesEdge.setLabel(GraphSchema.EDGE_LABEL_RESEARCHES);
				researchesEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_RESEARCHES);
				researchesEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_ID, nctId);
				g.addEdge(researchesEdge);

			});

			Edge consponsorEdge = gm.factory().newEdge(collabVertex, ctVertex, true);
			consponsorEdge.setLabel(GraphSchema.EDGE_LABEL_CONSPONSOR);
			consponsorEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_CONSPONSOR);
			consponsorEdge.setAttribute(GraphSchema.EDGE_PROPERTY_NCT_ID, nctId);
			g.addEdge(consponsorEdge);

		});

		// trial -> conditions
		studyConditions.forEach(c -> {
			Node conditionVertex = getOrCreateConditionVertex(gm, c);

			Edge coversEdge = gm.factory().newEdge(ctVertex, conditionVertex, true);
			coversEdge.setLabel(GraphSchema.EDGE_LABEL_COVERS);
			coversEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_COVERS);
			g.addEdge(coversEdge);
		});

		// trial -> locations
		study.getLocation().forEach(l -> {
			Node locationVertex = getOrCreateLocationVertex(gm, l.getFacility());
			String facilityName = getStringOrEmpty(l.getFacility().getName());

			Edge locationEdge = gm.factory().newEdge(ctVertex, locationVertex, true);
			locationEdge.setLabel(GraphSchema.EDGE_LABEL_LOCATION);
			locationEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LABEL, GraphSchema.EDGE_LABEL_LOCATION);
			locationEdge.setAttribute(GraphSchema.EDGE_PROPERTY_LOCATION_NAME, facilityName);
			g.addEdge(locationEdge);
		});

	}

	/**
	 * 
	 * @param startDateStr
	 * @return
	 * @throws ParseException
	 */
	private int getYear(String startDateStr) throws ParseException {
		Date startDate = NCT_DATE_FORMAT_1.parse(startDateStr);
		Calendar c = Calendar.getInstance();
		c.setTime(startDate);
		int startYear = c.get(Calendar.YEAR);
		if (startYear <= 31) {
			startDate = NCT_DATE_FORMAT_2.parse(startDateStr);
			c.setTime(startDate);
			startYear = c.get(Calendar.YEAR);
		}
		return startYear;
	}

	/**
	 * 
	 * @param gm
	 * @param facility
	 * @return
	 */
	private Node getOrCreateLocationVertex(GraphModel gm, FacilityStruct facility) {
		AddressStruct locationAddress = facility.getAddress();

		String city = getStringOrEmpty(locationAddress.getCity());
		String state = getStringOrEmpty(locationAddress.getState());
		String zip = getStringOrEmpty(locationAddress.getZip());
		String country = getStringOrEmpty(locationAddress.getCountry());
		String locationString = city + " " + state + " " + zip + " " + country;

		DirectedGraph g = gm.getDirectedGraph();

		Optional<Node> findFirst = g.getNodes().toCollection().stream()
				.filter(n -> n.getAttributeKeys().contains(GraphSchema.VERTEX_PROPERTY_LOCATION_FULL_ADDRESS)
						&& locationString.equals(n.getAttribute(GraphSchema.VERTEX_PROPERTY_LOCATION_FULL_ADDRESS)))
				.findFirst();

		Node locationVertex = null;
		if (findFirst.isPresent()) {
			locationVertex = findFirst.get();
		} else {
			LatLng coords = locationCoordMap.get(locationString);
			if (null == coords) {

				try {
					GeocodingApiRequest geocodeRequest = GeocodingApi.newRequest(googleGeoContext);
					ComponentFilter countryFilter = ComponentFilter.country(country);
					ComponentFilter localityFilter = ComponentFilter.locality(city);
					if (!zip.isEmpty()) {
						ComponentFilter zipFilter = ComponentFilter.postalCode(zip);
						geocodeRequest.components(countryFilter, localityFilter, zipFilter);
					} else {
						geocodeRequest.components(countryFilter, localityFilter);
					}
					GeocodingResult[] results = geocodeRequest.await();
					if (results.length > 0) {
						coords = results[0].geometry.location;
						locationCoordMap.put(locationString, coords);
					}
				} catch (Exception e) {
					LOG.log(Level.WARNING, "Unable to determine coordinates for address: " + locationString
							+ " due to: " + e.getLocalizedMessage(), e);
				}
			}

			locationVertex = gm.factory().newNode();
			locationVertex.setLabel(GraphSchema.VERTEX_LABEL_LOCATION);
			locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_LABEL_V, GraphSchema.VERTEX_LABEL_LOCATION);
			locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_LOCATION_FULL_ADDRESS, locationString);
			if (coords != null) {
				locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_LOCATION_LATITUDE, coords.lat);
				locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_LOCATION_LONGITUDE, coords.lng);
			}
			locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_ADDRESS_CITY, city);
			locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_ADDRESS_STATE,
					stateAbbrev.getOrDefault(state, state));
			locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_ADDRESS_ZIP, zip);
			locationVertex.setAttribute(GraphSchema.VERTEX_PROPERTY_ADDRESS_COUNTRY, country);
			g.addNode(locationVertex);
		}
		return locationVertex;

	}

	/**
	 * 
	 * @param gm
	 * @param intv
	 * @return
	 */
	private Node getOrCreateIntervention(GraphModel gm, InterventionStruct intv) {
		DirectedGraph g = gm.getDirectedGraph();

		InterventionTypeEnum iType = intv.getInterventionType();
		String interventionName = intv.getInterventionName();
		Optional<Node> findFirst = g.getNodes().toCollection().stream()
				.filter(n -> n.getAttributeKeys().contains(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_NAME)
						&& interventionName.equals(n.getAttribute(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_NAME)))
				.findFirst();

		Node iVt = null;
		if (findFirst.isPresent()) {
			iVt = findFirst.get();
		} else {
			iVt = gm.factory().newNode();
			iVt.setLabel(GraphSchema.VERTEX_LABEL_INTERVENTION);
			iVt.setAttribute(GraphSchema.VERTEX_PROPERTY_LABEL_V, GraphSchema.VERTEX_LABEL_INTERVENTION);
			iVt.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_TYPE, iType.toString());
			iVt.setAttribute(GraphSchema.VERTEX_PROPERTY_NCT_INTERVENTION_NAME, interventionName);
			g.addNode(iVt);
		}
		return iVt;
	}

	/**
	 * 
	 * @param gm
	 * @param conditionName
	 * @return
	 */
	private Node getOrCreateConditionVertex(GraphModel gm, String conditionName) {
		DirectedGraph g = gm.getDirectedGraph();

		String c2 = getNormalizedConditionName(conditionName);
		Optional<Node> findFirst = g.getNodes().toCollection().stream()
				.filter(n -> n.getAttributeKeys().contains(GraphSchema.VERTEX_PROPERTY_CONDITION_NAME)
						&& c2.equals(n.getAttribute(GraphSchema.VERTEX_PROPERTY_CONDITION_NAME)))
				.findFirst();

		Node cVt = null;
		if (findFirst.isPresent()) {
			cVt = findFirst.get();
		} else {
			cVt = gm.factory().newNode();
			cVt.setLabel(GraphSchema.VERTEX_LABEL_CONDITION);
			cVt.setAttribute(GraphSchema.VERTEX_PROPERTY_LABEL_V, GraphSchema.VERTEX_LABEL_CONDITION);
			cVt.setAttribute(GraphSchema.VERTEX_PROPERTY_CONDITION_RAW, conditionName);
			cVt.setAttribute(GraphSchema.VERTEX_PROPERTY_CONDITION_NAME, c2);
			g.addNode(cVt);
		}
		return cVt;
	}

	/**
	 * 
	 * @param gm
	 * @param sponsorAgency
	 * @param agencyClass
	 * @return
	 */
	private Node getOrCreateSponsorVertex(GraphModel gm, String sponsorAgency, String agencyClass) {
		DirectedGraph g = gm.getDirectedGraph();

		Optional<Node> findFirst = g.getNodes().toCollection().stream()
				.filter(n -> n.getAttributeKeys().contains(GraphSchema.VERTEX_PROPERTY_SPONSOR_NAME)
						&& sponsorAgency.equals(n.getAttribute(GraphSchema.VERTEX_PROPERTY_SPONSOR_NAME)))
				.findFirst();

		Node sv = null;
		if (findFirst.isPresent()) {
			sv = findFirst.get();
		} else {
			sv = gm.factory().newNode();
			sv.setLabel(GraphSchema.VERTEX_LABEL_SPONSOR);
			sv.setAttribute(GraphSchema.VERTEX_PROPERTY_LABEL_V, GraphSchema.VERTEX_LABEL_SPONSOR);
			sv.setAttribute(GraphSchema.VERTEX_PROPERTY_SPONSOR_NAME, sponsorAgency);
			sv.setAttribute(GraphSchema.VERTEX_PROPERTY_SPONSOR_CLASS, agencyClass);
			g.addNode(sv);
		}
		return sv;
	}

	/**
	 * 
	 * @param c
	 * @return
	 */
	private String getNormalizedConditionName(String c) {
		String c3 = nctConditionDisease.get(c);
		if (c3 == null) {
			String c2 = c.replaceAll("-", " ").toLowerCase();

			String cLowerCase = c.toLowerCase();
			for (String surfaceForm : diseaseCui.keySet()) {
				if (surfaceForm.equals(surfaceForm.toUpperCase())) {
					continue;
				}
				try {
					String sfLowerCase = surfaceForm.toLowerCase();
					int sfIndex = cLowerCase.indexOf(sfLowerCase);
					if (sfIndex > -1) {
						if (cLowerCase.length() == sfLowerCase.length()
								|| (sfIndex == 0 && cLowerCase.charAt(sfLowerCase.length()) == ' ')
								|| (sfIndex > 0 && sfIndex + sfLowerCase.length() == cLowerCase.length()
										&& cLowerCase.charAt(sfIndex - 1) == ' ')
								|| (sfIndex > 0 && cLowerCase.charAt(sfIndex - 1) == ' '
										&& cLowerCase.charAt(sfIndex + sfLowerCase.length()) == ' ')) {
							if (c3 == null || surfaceForm.length() > c3.length()) {
								c3 = surfaceForm;
							}
						}
					}
				} catch (StringIndexOutOfBoundsException e) {
					LOG.log(Level.WARNING, "ugh::" + c + " ::" + surfaceForm, e);
				}
			}
			if (c3 == null) {
				c3 = c2;
			}
			nctConditionDisease.put(c, c3);
		}
		if (LOG.isLoggable(Level.FINER)) {
			LOG.finer(c + " :: " + c3);
		}

		return c3;
	}

	/**
	 * 
	 * @param parm
	 * @return
	 */
	private String getStringOrEmpty(String parm) {
		return parm != null ? parm : "";
	}

	private static void createLayoutRunnable(GraphModel gm) {
		AutoLayout autoLayout = new AutoLayout(100, TimeUnit.SECONDS);
		LOG.info("Processing layout for " + 100 + " seconds.");
		autoLayout.setGraphModel(gm);
		FruchtermanReingold firstLayout = new FruchtermanReingold(null);
		firstLayout.setGravity(10.0d);
		firstLayout.setSpeed(1d);
		firstLayout.setArea(10000f);

		// ForceAtlasLayout secondLayout = new ForceAtlasLayout(null);
		// AutoLayout.DynamicProperty adjustBySizeProperty = AutoLayout
		// .createDynamicProperty("forceAtlas.adjustSizes.name", Boolean.TRUE,
		// 0.1f);// True
		// // after 10% of layout time
		// AutoLayout.DynamicProperty repulsionProperty = AutoLayout
		// .createDynamicProperty("forceAtlas.repulsionStrength.name", new
		// Double(500.), 0f);// 500
		// for the complete period
		// autoLayout.addLayout(secondLayout, 0.5f,
		// new AutoLayout.DynamicProperty[] { adjustBySizeProperty,
		// repulsionProperty });
		autoLayout.addLayout(firstLayout, 1.0f);
		autoLayout.execute();
	}
}
