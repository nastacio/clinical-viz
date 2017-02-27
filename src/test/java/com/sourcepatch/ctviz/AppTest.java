package com.sourcepatch.ctviz;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.gephi.graph.api.DirectedGraph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.io.exporter.api.ExportController;
import org.gephi.io.generator.plugin.RandomGraph;
import org.gephi.io.importer.api.Container;
import org.gephi.io.importer.api.ImportController;
import org.gephi.io.processor.plugin.DefaultProcessor;
import org.gephi.layout.plugin.AutoLayout;
import org.gephi.layout.plugin.fruchterman.FruchtermanReingold;
import org.gephi.project.api.Project;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.junit.Test;
import org.openide.util.Lookup;

/**
 * Unit test for simple App.
 */
public class AppTest {

	/**
	 * 
	 */
	@Test
	public void gephiTest() {

		// Init a project - and therefore a workspace
		ProjectController pc = Lookup.getDefault().lookup(ProjectController.class);
		pc.newProject();
		Project p = pc.getProjects().getProjects()[0];
		final Workspace workspace1 = pc.newWorkspace(p);

		// Generate a new random graph into a container
		Container container = Lookup.getDefault().lookup(Container.Factory.class).newContainer();
		RandomGraph randomGraph = new RandomGraph();
		randomGraph.setNumberOfNodes(500);
		randomGraph.setWiringProbability(0.005);
		randomGraph.generate(container.getLoader());

		// Append container to graph structure
		ImportController importController = Lookup.getDefault().lookup(ImportController.class);
		importController.process(container, new DefaultProcessor(), workspace1);

		// See if graph is well imported
		GraphModel graphModel = Lookup.getDefault().lookup(GraphController.class).getGraphModel(workspace1);
		DirectedGraph graph = graphModel.getDirectedGraph();
		System.out.println("Nodes: " + graph.getNodeCount());
		System.out.println("Edges: " + graph.getEdgeCount());

		// Run Tasks and wait for termination in the current thread
		createLayoutRunnable(workspace1, graphModel);

		// Export
		ExportController ec = Lookup.getDefault().lookup(ExportController.class);
		try {
			pc.openWorkspace(workspace1);
			ec.exportFile(new File("out/parallel_workspace1.pdf"));

			// Exporter exporter = ec.getExporter("gexf");
			ec.exportFile(new File("out/parallel_workspace1.gexf"), workspace1);
			// exporter.setWorkspace(workspace2);
			// CharacterExporter characterExporter = (CharacterExporter)
			// exporter;
			// StringWriter stringWriter = new StringWriter();
			// ec.exportWriter(stringWriter, characterExporter);
			// String result = stringWriter.toString();
			// System.out.println(result);

		} catch (IOException ex) {
			ex.printStackTrace();
		}

	}

	private void createLayoutRunnable(Workspace workspace1, GraphModel graphModel) {
		AutoLayout autoLayout = new AutoLayout(1, TimeUnit.MINUTES);
		autoLayout.setGraphModel(graphModel);
		FruchtermanReingold firstLayout = new FruchtermanReingold(null);
		firstLayout.setGravity(5.0d);
		// ForceAtlasLayout secondLayout = new ForceAtlasLayout(null);
		// AutoLayout.DynamicProperty adjustBySizeProperty = AutoLayout
		// .createDynamicProperty("forceAtlas.adjustSizes.name", Boolean.TRUE,
		// 0.1f);// True
		// // after
		// // 10%
		// // of
		// // layout
		// // time
		// AutoLayout.DynamicProperty repulsionProperty = AutoLayout
		// .createDynamicProperty("forceAtlas.repulsionStrength.name", new
		// Double(500.), 0f);// 500
		// // for
		// // the
		// // complete
		// // period
		autoLayout.addLayout(firstLayout, 1.0f);
		// autoLayout.addLayout(secondLayout, 0.5f,
		// new AutoLayout.DynamicProperty[] { adjustBySizeProperty,
		// repulsionProperty });
		autoLayout.execute();
	}
}
