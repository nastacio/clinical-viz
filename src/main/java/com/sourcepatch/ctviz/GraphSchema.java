/********************************************************* {COPYRIGHT-TOP} ***
 * ctgov-viz
 *
 * Public domain
 * MIT License
 * 
 * https://github.com/nastacio/clinical-viz
 ********************************************************* {COPYRIGHT-END} **/
package com.sourcepatch.ctviz;

/**
 * Labels and property names for nodes and edges on the graphs.
 * 
 * @author Denilson Nastacio
 */
public interface GraphSchema {

	String VERTEX_LABEL_CONDITION = "condition";
	String VERTEX_LABEL_INTERVENTION = "intervention";
	String VERTEX_LABEL_LOCATION = "location";
	String VERTEX_LABEL_SPONSOR = "sponsor";
	String VERTEX_LABEL_TRIAL = "trial";

	String VERTEX_PROPERTY_LABEL_V = "labelV";

	String VERTEX_PROPERTY_ADDRESS_COUNTRY = "country";
	String VERTEX_PROPERTY_ADDRESS_ZIP = "zip";
	String VERTEX_PROPERTY_ADDRESS_STATE = "state";
	String VERTEX_PROPERTY_ADDRESS_CITY = "city";
	String VERTEX_PROPERTY_CONDITION_NAME = "condition_name";
	String VERTEX_PROPERTY_CONDITION_RAW = "nct_condition";
	String VERTEX_PROPERTY_NCT_INTERVENTION_TYPE = "intervention_type";
	String VERTEX_PROPERTY_NCT_INTERVENTION_NAME = "intervention_name";
	String VERTEX_PROPERTY_LOCATION_FULL_ADDRESS = "location_full_address";
	String VERTEX_PROPERTY_LOCATION_LATITUDE = "lat";
	String VERTEX_PROPERTY_LOCATION_LONGITUDE = "lng";
	String VERTEX_PROPERTY_SPONSOR_CLASS = "class";
	String VERTEX_PROPERTY_SPONSOR_NAME = "sponsor_name";

	String VERTEX_PROPERTY_NCT_ENROLLMENT = "enrollment";
	String VERTEX_PROPERTY_NCT_GENDER = "gender";
	String VERTEX_PROPERTY_NCT_INTERVENTION_MODEL = "intervention_model";
	String VERTEX_PROPERTY_NCT_MASKING = "masking";
	String VERTEX_PROPERTY_NCT_MAX_AGE = "maxAge";
	String VERTEX_PROPERTY_NCT_MIN_AGE = "minAge";
	String VERTEX_PROPERTY_NCT_ORG_STUDY_ID = "org_study_id";
	String VERTEX_PROPERTY_NCT_OVERALL_STATUS = "overall_status";
	String VERTEX_PROPERTY_NCT_PHASE = "phase";
	String VERTEX_PROPERTY_NCT_PRIMARY_PURPOSE = "primary_purpose";
	String VERTEX_PROPERTY_NCT_START_YEAR = "start_year";
	String VERTEX_PROPERTY_NCT_STUDY_ID = "study_id";
	String VERTEX_PROPERTY_NCT_STUDY_TYPE = "study_type";
	String VERTEX_PROPERTY_NCT_TITLE = "title";

	String EDGE_LABEL_CONSPONSOR = "consponsors";
	String EDGE_LABEL_COLLABORATES = "collaborates";
	String EDGE_LABEL_COVERS = "covers";
	String EDGE_LABEL_LEADS = "leads";
	String EDGE_LABEL_LOCATION = "locates";
	String EDGE_LABEL_RESEARCHES = "researches";
	String EDGE_LABEL_SPONSORS = "sponsors";
	String EDGE_LABEL_TESTS = "tests";

	String EDGE_PROPERTY_LABEL = "labelE";
	String EDGE_PROPERTY_LOCATION_NAME = "location_name";
	String EDGE_PROPERTY_NCT_ID = "nct";
	String EDGE_PROPERTY_NCT_INTERVENTION_TYPE = VERTEX_PROPERTY_NCT_INTERVENTION_TYPE;

}
