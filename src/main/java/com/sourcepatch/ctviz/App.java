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
import java.io.FileOutputStream;
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
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLWriter;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

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

	private static final String VERTEX_PROPERTY_INTERVENTION_NAME = "intervention_name";
	private static final String VERTEX_PROPERTY_CONDITION_RAW = "nct_condition";
	private static final String VERTEX_PROPERTY_LOCATION_FULL_ADDRESS = "location_full_address";
	private static final String VERTEX_PROPERTY_CONDITION_NAME = "condition_name";
	private static final String VERTEX_PROPERTY_SPONSOR_NAME = "sponsor_name";

	private static final String NCT_DATE_PATTERN_1 = "MMMMM yyyy";
	private static final String NCT_DATE_PATTERN_2 = "MMMMM dd, yyyy";
	private static final SimpleDateFormat NCT_DATE_FORMAT_1 = new SimpleDateFormat(NCT_DATE_PATTERN_1);
	private static final SimpleDateFormat NCT_DATE_FORMAT_2 = new SimpleDateFormat(NCT_DATE_PATTERN_2);

	private Map<String, String> cuiDisease = new TreeMap<>();
	private Map<String, String> diseaseCui = new TreeMap<>();
	private Map<String, String> nctConditionDisease = new TreeMap<>();

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

		app.loadConditionMaps();

		Graph g = app.generateGraph(args[0]);
		Path outGraph = Paths.get("out/ctgraph.graphml");
		Files.createDirectories(outGraph.getParent());
		GraphMLWriter gmlWriter = GraphMLWriter.build().create();
		gmlWriter.writeGraph(new FileOutputStream(outGraph.toFile()), g);

		LOG.info("Output graph written to " + outGraph.toFile().getAbsolutePath());

		Path outConditionPhrases = Paths.get("out/ct.condition.phrases.txt");
		try (PrintWriter pw = new PrintWriter(outConditionPhrases.toFile())) {
			g.traversal().V().has(VERTEX_PROPERTY_CONDITION_RAW)
					.forEachRemaining(v -> pw.println(v.value(VERTEX_PROPERTY_CONDITION_RAW).toString() + " ."));
		}

		Path outInterventionPhrases = Paths.get("out/ct.intervention.phrases.txt");
		try (PrintWriter pw = new PrintWriter(outInterventionPhrases.toFile())) {
			g.traversal().V().has(VERTEX_PROPERTY_INTERVENTION_NAME)
					.forEachRemaining(v -> pw.println(v.value(VERTEX_PROPERTY_INTERVENTION_NAME).toString() + " ."));
		}

		LOG.info("Output conditions written to " + outConditionPhrases.toFile().getAbsolutePath());
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
	 * @param searchTerm
	 * @return
	 * @throws Exception
	 */
	public Graph generateGraph(String searchTerm) throws Exception {
		Graph g = TinkerGraph.open();

		String instancePath = "com.sourcepatch.ctviz.ctgov";
		JAXBContext jc = JAXBContext.newInstance(instancePath);
		Unmarshaller u = jc.createUnmarshaller();

		String urlStr = "https://clinicaltrials.gov/ct2/results/download?down_stds=all&down_typ=results&down_flds=shown&down_fmt=plain&show_down=Y&term=";
		urlStr += searchTerm;
		URL url = new URL(urlStr);
		int trialCount = 0;
		try (InputStream is = url.openStream(); ZipInputStream zis = new ZipInputStream(is)) {

			ZipEntry ctXmlEntry = null;
			while ((ctXmlEntry = zis.getNextEntry()) != null) {

				LOG.info(ctXmlEntry.getName());

				Path tf = Files.createTempFile("nct", "xml");
				Files.copy(zis, tf, StandardCopyOption.REPLACE_EXISTING);

				try (InputStream ftf = Files.newInputStream(tf)) {
					Object obj = u.unmarshal(ftf);
					ClinicalStudy study = (ClinicalStudy) obj;
					Files.delete(tf);

					addStudyToGraph(study, g);
					trialCount++;
				}
			}
		}

		LOG.info("Processed " + trialCount + " clinical trials into graph: " + g.toString());
		return g;
	}

	/*
	 * Private methods.
	 */

	/**
	 * 
	 * @param study
	 * @param g
	 */
	private void addStudyToGraph(ClinicalStudy study, Graph g) {

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
		Vertex ctVertex = g.addVertex(T.label, "trial", "study_id", nctId, "org_study_id", orgStudyId, "title",
				briefTitle, "overall_status", overallStatus, "phase", phase, "study_type", studyType, "enrollment",
				enrollment, "gender", genderStr, "minAge", minAge, "maxAge", maxAge);

		StudyDesignInfoStruct designInfo = study.getStudyDesignInfo();
		if (designInfo != null) {
			String interventionModel = getStringOrEmpty(designInfo.getInterventionModel());
			String primaryPurpose = getStringOrEmpty(designInfo.getPrimaryPurpose());
			String masking = getStringOrEmpty(designInfo.getMasking());

			ctVertex.property("intervention_model", interventionModel);
			ctVertex.property("primary_purpose", primaryPurpose);
			ctVertex.property("masking", masking);
		}

		if (study.getStudyType().equals(StudyTypeEnum.INTERVENTIONAL)) {
			List<InterventionStruct> interventions = study.getIntervention();
			for (InterventionStruct intv : interventions) {
				Vertex interventionVertex = getOrCreateIntervention(g, intv);
				ctVertex.addEdge("tests", interventionVertex, "intervention_type",
						intv.getInterventionType().toString());
			}
		}

		if (study.getStartDate() != null) {
			String startDateStr = study.getStartDate().getValue();
			try {
				int startYear = getYear(startDateStr);
				if (startYear < 1900) {
					LOG.warning(nctId + " has a likely invalid start year: " + startDateStr);
				} else {
					ctVertex.property("start_year", startYear);
				}
			} catch (ParseException e) {
				LOG.warning(nctId + " does not have a valid start year: " + startDateStr);
			}
		}

		SponsorsStruct ssList = study.getSponsors();
		String sponsorAgency = ssList.getLeadSponsor().getAgency();
		AgencyClassEnum agencyClass = ssList.getLeadSponsor().getAgencyClass();
		final Vertex sv = getOrCreateSponsorVertex(g, sponsorAgency, agencyClass.toString());

		List<String> studyConditions = study.getCondition();

		// sponsor -> condition
		// sponsor -> trial
		studyConditions.forEach(c -> {
			Vertex conditionVertex = getOrCreateConditionVertex(g, c);
			sv.addEdge("researches", conditionVertex, "nct", nctId);
			sv.addEdge("sponsors", ctVertex, "nct", nctId);
		});

		study.getSponsors().getCollaborator().forEach(collabAgency -> {
			// sponsor -> collaborators
			String collab = collabAgency.getAgency();

			AgencyClassEnum collabClass = collabAgency.getAgencyClass();
			final Vertex collabVertex = getOrCreateSponsorVertex(g, collab, collabClass.toString());

			sv.addEdge("leads", collabVertex, "nct", nctId);
			collabVertex.addEdge("collaborates", sv, "nct", nctId);

			// collaborators -> condition
			// collaborators -> trial
			// trial -> conditions
			studyConditions.forEach(c -> {
				Vertex conditionVertex = getOrCreateConditionVertex(g, c);
				collabVertex.addEdge("researches", conditionVertex, "nct", nctId);
				collabVertex.addEdge("cosponsors", ctVertex, "nct", nctId);
				ctVertex.addEdge("covers", conditionVertex);
			});

			// trial -> locations
			study.getLocation().forEach(l -> {
				Vertex locationVertex = getOrCreateLocationVertex(g, l.getFacility());
				String facilityName = getStringOrEmpty(l.getFacility().getName());
				ctVertex.addEdge("location", locationVertex, "location_name", facilityName);
			});

		});

	}

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
	 * @param g
	 * @param facility
	 * @return
	 */
	private Vertex getOrCreateLocationVertex(Graph g, FacilityStruct facility) {
		AddressStruct locationAddress = facility.getAddress();

		String city = getStringOrEmpty(locationAddress.getCity());
		String state = getStringOrEmpty(locationAddress.getState());
		String zip = getStringOrEmpty(locationAddress.getZip());
		String country = getStringOrEmpty(locationAddress.getCountry());
		String locationString = city + " " + state + " " + zip + " " + country;
		GraphTraversal<Vertex, Vertex> locationIter = g.traversal().V().has(VERTEX_PROPERTY_LOCATION_FULL_ADDRESS,
				locationString);
		final Vertex locationVertex = locationIter.hasNext() ? locationIter.next()
				: g.addVertex(T.label, "location", VERTEX_PROPERTY_LOCATION_FULL_ADDRESS, locationString, "city", city,
						"state", state, "zip", zip, "country", country);
		return locationVertex;
	}

	/**
	 * 
	 * @param g
	 * @param intv
	 * @return
	 */
	private Vertex getOrCreateIntervention(Graph g, InterventionStruct intv) {
		InterventionTypeEnum iType = intv.getInterventionType();
		String interventionName = intv.getInterventionName();
		GraphTraversal<Vertex, Vertex> intvIter = g.traversal().V().has(VERTEX_PROPERTY_INTERVENTION_NAME,
				interventionName);
		final Vertex iVt = intvIter.hasNext() ? intvIter.next()
				: g.addVertex(T.label, "intervention", "intervention_type", iType.toString(),
						VERTEX_PROPERTY_INTERVENTION_NAME, interventionName);
		;
		return iVt;
	}

	/**
	 * 
	 * @param g
	 * @param conditionName
	 * @return
	 */
	private Vertex getOrCreateConditionVertex(Graph g, String conditionName) {
		String c2 = getNormalizedConditionName(conditionName);
		GraphTraversal<Vertex, Vertex> conditionIter = g.traversal().V().has(VERTEX_PROPERTY_CONDITION_NAME, c2);
		final Vertex cVt = conditionIter.hasNext() ? conditionIter.next()
				: g.addVertex(T.label, "condition", VERTEX_PROPERTY_CONDITION_RAW, conditionName,
						VERTEX_PROPERTY_CONDITION_NAME, c2);
		return cVt;
	}

	/**
	 * 
	 * @param g
	 * @param sponsorAgency
	 * @param agencyClass
	 * @return
	 */
	private Vertex getOrCreateSponsorVertex(Graph g, String sponsorAgency, String agencyClass) {
		GraphTraversal<Vertex, Vertex> sponsorIter = g.traversal().V().has(VERTEX_PROPERTY_SPONSOR_NAME, sponsorAgency);
		final Vertex sv = sponsorIter.hasNext() ? sponsorIter.next()
				: g.addVertex(T.label, "sponsor", VERTEX_PROPERTY_SPONSOR_NAME, sponsorAgency, "class", agencyClass);
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

	private String getStringOrEmpty(String parm) {
		return parm != null ? parm : "";
	}
}
