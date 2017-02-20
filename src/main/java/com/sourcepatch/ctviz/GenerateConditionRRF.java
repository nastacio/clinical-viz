/**
 * 
 */
package com.sourcepatch.ctviz;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author nastacio
 *
 */
public class GenerateConditionRRF {

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		GenerateConditionRRF gcr = new GenerateConditionRRF();
		gcr.run(args);
	}

	/**
	 * 
	 * @param args
	 */
	private void run(String[] args) throws Exception {
		Set<String> diseaseCuis = new TreeSet<>(Files.readAllLines(Paths.get("src/main/resources/cui.conditions.csv")));
		try (BufferedReader br = new BufferedReader(
				new FileReader("MRCONSO.RRF"));
				ZipOutputStream zos = new ZipOutputStream(
						new FileOutputStream("src/main/resources/MRCONSO.conditions.zip"));
				PrintWriter pw = new PrintWriter(zos)) {
			zos.putNextEntry(new ZipEntry("dsyn.rrf"));
			String line = null;
			while ((line = br.readLine()) != null) {
				String cui = line.split("\\|")[0];
				if (diseaseCuis.contains(cui)) {
					pw.println(line);
				}
			}
		}
	}
}
