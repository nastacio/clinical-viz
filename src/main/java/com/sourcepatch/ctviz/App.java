/********************************************************* {COPYRIGHT-TOP} ***
 * ctgov-viz
 *
 * Public domain
 * MIT License
 * 
 * https://github.com/nastacio/clinical-viz
 ********************************************************* {COPYRIGHT-END} **/
package com.sourcepatch.ctviz;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
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
import com.sourcepatch.ctviz.ctgov.SponsorsStruct;

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

	private static final String VERTEX_PROPERTY_LOCATION_FULL_ADDRESS = "location_full_address";
	private static final String VERTEX_PROPERTY_CONDITION_NAME = "condition_name";
	private static final String VERTEX_PROPERTY_SPONSOR_NAME = "sponsor_name";

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
		Graph g = app.generateGraph(args[0]);

		Path outGraph = Paths.get("out/ctgraph.graphml");
		Files.createDirectories(outGraph.getParent());
		GraphMLWriter gmlWriter = GraphMLWriter.build().create();
		gmlWriter.writeGraph(new FileOutputStream(outGraph.toFile()), g);

		LOG.info("Output graph written to " + outGraph.toFile().getAbsolutePath());
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
		Vertex clinicalTrial = g.addVertex(T.label, "trial", "study_id", nctId, "org_study_id", orgStudyId, "title",
				briefTitle, "overall_status", overallStatus, "phase", phase, "study_type", studyType, "enrollment",
				enrollment, "gender", genderStr, "minAge", minAge, "maxAge", maxAge);

		if (study.getStartDate() != null) {
			String startDateStr = study.getStartDate().getValue();
			try {
				int startYear = getYear(startDateStr);
				if (startYear < 1900) {
					LOG.warning(nctId + " has a likely invalid start year: " + startDateStr);
				} else {
					clinicalTrial.property("start_year", startYear);
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
			sv.addEdge("sponsors", clinicalTrial, "nct", nctId);
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
				collabVertex.addEdge("cosponsors", clinicalTrial, "nct", nctId);
				clinicalTrial.addEdge("covers", conditionVertex);
			});

			// trial -> locations
			study.getLocation().forEach(l -> {
				Vertex locationVertex = getOrCreateLocationVertex(g, l.getFacility());
				String facilityName = getStringOrEmpty(l.getFacility().getName());
				clinicalTrial.addEdge("location", locationVertex, "location_name", facilityName);
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

	private Vertex getOrCreateConditionVertex(Graph g, String conditionName) {
		String c2 = getNormalizedConditionName(conditionName);
		GraphTraversal<Vertex, Vertex> conditionIter = g.traversal().V().has(VERTEX_PROPERTY_CONDITION_NAME, c2);
		final Vertex cVt = conditionIter.hasNext() ? conditionIter.next()
				: g.addVertex(T.label, "condition", VERTEX_PROPERTY_CONDITION_NAME, c2);
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

	private String getNormalizedConditionName(String c) {
		String c2 = c.replaceAll("-", " ").toLowerCase();
		return c2;
	}

	private String getStringOrEmpty(String parm) {
		return parm != null ? parm : "";
	}
}
