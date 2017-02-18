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
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.io.graphml.GraphMLWriter;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;

import com.sourcepatch.ctviz.ctgov.ClinicalStudy;
import com.sourcepatch.ctviz.ctgov.EnrollmentStruct;
import com.sourcepatch.ctviz.ctgov.SponsorsStruct;
import com.sourcepatch.ctviz.ctgov.StudyTypeEnum;

/**
 * Converts a clinical trial search into a graph visualization.
 * 
 * @author Denilson Nastacio
 */
public class App {

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

		Map<String, Vertex> sponsorMap = new TreeMap<>();
		Map<String, Vertex> conditionMap = new TreeMap<>();

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

					addStudyToGraph(study, g, sponsorMap, conditionMap);
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
	 * @param sponsorMap
	 * @param conditionMap
	 */
	private void addStudyToGraph(ClinicalStudy study, Graph g, Map<String, Vertex> sponsorMap,
			Map<String, Vertex> conditionMap) {

		String nctId = study.getIdInfo().getNctId();

		//
		// Add nodes
		//

		String orgStudyId = study.getIdInfo().getOrgStudyId() != null ? study.getIdInfo().getOrgStudyId() : "";
		String briefTitle = study.getBriefTitle() != null ? study.getBriefTitle() : "";
		String overallStatus = study.getOverallStatus() != null ? study.getOverallStatus() : "";
		String phase = study.getPhase() != null ? study.getPhase().toString() : "";
		String studyType = study.getStudyType() != null ? study.getStudyType().toString() : "";
		Long enrollment = study.getEnrollment() != null ? study.getEnrollment().getValue().longValue() : 0;
		Vertex ct = g.addVertex(T.label, "trial", "study_id", nctId, "org_study_id", orgStudyId, "title", briefTitle,
				"overall_status", overallStatus, "phase", phase, "study_type", studyType, "enrollment", enrollment);

		SponsorsStruct ssList = study.getSponsors();
		String sponsorAgency = ssList.getLeadSponsor().getAgency();
		final Vertex sv1 = sponsorMap.get(sponsorAgency);
		// String studyType = study.getStudyType() != null ?
		// study.getStudyType().toString()
		// : "none";
		// String whyStopped = study.getWhyStopped() != null ?
		// study.getWhyStopped() : "none";
		// String startDate = study.getStartDate() != null ?
		// study.getStartDate() : "none";
		// String completionDate = study.getCompletionDate() != null ?
		// study.getCompletionDate()
		// .getValue() : "none";
		final Vertex sv = sv1 != null ? sv1
				: g.addVertex(T.label, "sponsor", "name", sponsorAgency, "class", sponsorAgency);
		// , "studyType", studyType, "startDate",
		// startDate, "endDate", completionDate, "phase",
		// study.getPhase().toString(),
		// "whystopped", whyStopped);
		if (null == sv1) {
			sponsorMap.put(sponsorAgency, sv);
		}
		addCount(sv);

		study.getSponsors().getCollaborator().forEach(collabAgency -> {
			String collab = collabAgency.getAgency();
			final Vertex cv1 = sponsorMap.get(collab);
			final Vertex cv = cv1 != null ? cv1
					: g.addVertex(T.label, "sponsor", "name", collab, "class",
							collabAgency.getAgencyClass().toString());
			if (null == cv1) {
				sponsorMap.put(collab, cv);
			}
			addCount(cv);
		});

		study.getCondition().forEach(c -> {
			String c2 = getNormalizedConditionName(c);
			Vertex cVt = conditionMap.get(c2);
			if (cVt == null) {
				cVt = g.addVertex(T.label, "condition", "name", c2);
			}
			addCount(cVt);
			conditionMap.put(c2, cVt);
		});

		//
		// Add edges
		//

		// sponsor -> condition
		// sponsor -> trial
		study.getCondition().forEach(c -> {
			String c2 = getNormalizedConditionName(c);
			Vertex cVt = conditionMap.get(c2);
			sv.addEdge("researches", cVt, "nct", nctId);
			sv.addEdge("sponsors", ct, "nct", nctId);
		});

		study.getSponsors().getCollaborator().forEach(collabAgency -> {
			// sponsor -> collaborators
			String collab = collabAgency.getAgency();
			final Vertex cv = sponsorMap.get(collab);

			sv.addEdge("leads", cv, "nct", nctId);
			cv.addEdge("collaborates", sv, "nct", nctId);

			// collaborators -> condition
			// collaborators -> trial
			// trial -> conditons
			study.getCondition().forEach(c -> {
				String c2 = getNormalizedConditionName(c);
				Vertex cVt = conditionMap.get(c2);
				cv.addEdge("researches", cVt, "nct", nctId);
				cv.addEdge("cosponsors", ct, "nct", nctId);
				ct.addEdge("covers", cVt);
			});

		});

	}

	private String getNormalizedConditionName(String c) {
		String c2 = c.replaceAll("-", " ").toLowerCase();
		return c2;
	}

	private void addCount(Vertex cVt) {
		Integer count = (Integer) cVt.property("count").orElse(new Integer(0));
		cVt.property("count", count + 1);
	}
}
