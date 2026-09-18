package org.protege.editor.owl.server.http;

/**
 * Represents the service end-points provided by the HTTP server.
 *
 * @author Josef Hardi <johardi@stanford.edu> <br>
 * Stanford Center for Biomedical Informatics Research
 */
public class ServerEndpoints {

	public static final String ROOT_PATH = "/nci_protege";

	public static final String LOGIN = ROOT_PATH + "/login";

	public static final String PROJECT = ROOT_PATH + "/meta/project";
	public static final String PROJECT_UPDATE = ROOT_PATH + "/meta/project/update";
	public static final String PROJECT_SNAPSHOT = ROOT_PATH + "/meta/project/snapshot";
	public static final String PROJECT_INDEX = ROOT_PATH + "/meta/project/index";
	public static final String PROJECT_EXPORT = ROOT_PATH + "/meta/project/export";
	public static final String PROJECT_CLASSIFY = ROOT_PATH + "/meta/project/classify";
	public static final String PROJECTS = ROOT_PATH + "/meta/projects";
	public static final String PROJECTS_UNCLASSIFIED = ROOT_PATH + "/meta/projects/unclassified";
	public static final String METAPROJECT = ROOT_PATH + "/meta/metaproject";
    public static final String SQUASH = ROOT_PATH + "/meta/squash";
    public static final String SERVER_STATUS = ROOT_PATH + "/meta/serverstatus";

	public static final String ALL_CHANGES = ROOT_PATH + "/all_changes"; 
	public static final String LATEST_CHANGES = ROOT_PATH + "/latest_changes"; 
	public static final String HEAD = ROOT_PATH + "/head";
	public static final String COMMIT = ROOT_PATH + "/commit";

	public static final String GEN_CODE = ROOT_PATH + "/gen_code";
	public static final String SET_CODEGEN_SEQ = ROOT_PATH + "/server/setcodegenseq";
	
	public static final String EVS_REC = ROOT_PATH + "/evs_record";
	public static final String EVS_CHECK_CREATE = ROOT_PATH + "/evs_check_create";
	public static final String EVS_HIST = ROOT_PATH + "/evs_history";
	public static final String GEN_CON_HIST = ROOT_PATH + "/gen_con_history";

	public static final String SERVER_RESTART = ROOT_PATH + "/server/restart";
	public static final String SERVER_STOP = ROOT_PATH + "/server/stop";
	public static final String SERVER_PAUSE = ROOT_PATH + "/server/pause";
	public static final String SERVER_RESUME = ROOT_PATH + "/server/resume";
	public static final String SERVER_SHUTDOWN = ROOT_PATH + "/server/shutdown";
	
}
