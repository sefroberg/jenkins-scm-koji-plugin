package org.fakekoji.api.http.rest;

import io.javalin.apibuilder.EndpointGroup;
import org.fakekoji.core.AccessibleSettings;
import org.fakekoji.core.utils.OToolParser;
import org.fakekoji.functional.Result;
import org.fakekoji.jobmanager.ConfigManager;
import org.fakekoji.jobmanager.JenkinsCliWrapper;
import org.fakekoji.jobmanager.ManagementException;
import org.fakekoji.jobmanager.manager.JDKVersionManager;
import org.fakekoji.jobmanager.manager.PlatformManager;
import org.fakekoji.jobmanager.manager.TaskVariantManager;
import org.fakekoji.jobmanager.model.JDKProject;
import org.fakekoji.jobmanager.model.JDKTestProject;
import org.fakekoji.jobmanager.model.Job;
import org.fakekoji.jobmanager.model.Project;
import org.fakekoji.jobmanager.project.JDKProjectManager;
import org.fakekoji.jobmanager.project.JDKProjectParser;
import org.fakekoji.jobmanager.project.JDKTestProjectManager;
import org.fakekoji.model.JDKVersion;
import org.fakekoji.model.OToolArchive;
import org.fakekoji.model.OToolBuild;
import org.fakekoji.model.Platform;
import org.fakekoji.storage.StorageException;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;

public class GetterAPI implements EndpointGroup {

    private static final String ERROR_PARAMETERS_EXPECTED = "Parameters expected";
    private static final String ERROR_PROJECT_NOT_FOUND = "Project not found";

    private static final String ARCHIVE = "archive";
    private static final String BUILD = "build";
    private static final String BUILDS = "builds";
    private static final String CONFIGS = "configs";
    private static final String FILE_DOWNLOAD = "fileDownload";
    private static final String JDK_VERSION = "jdkVersion";
    private static final String JENKINS_JOBS = "jenkinsJobs";
    private static final String JENKINS_JOB_ARCHIVE = "jenkinsJobArchive";
    private static final String JOBS = "jobs";
    private static final String PATH = "path";
    private static final String PORT = "port";
    private static final String PORTS = "ports"; // TODO
    private static final String PRODUCT = "product";
    private static final String PRODUCTS = "products";
    private static final String PROJECT = "project";
    private static final String PROJECTS = "projects";
    private static final String PLATFORMS = "platforms";
    private static final String KOJI_ARCHES = "kojiArches";
    private static final String REPOS = "repos";
    private static final String ROOT = "root";
    private static final String ROOTS = "roots"; // TODO
    private static final String SERVICE = "service";
    private static final String SSH = "ssh";
    private static final String TYPE = "type";
    private static final String WEBAPP = "webapp";
    private static final String XML_RPC = "xmlRpc";

    private static final String JDK_VERSIONS = "jdkVersions";
    private static final String HELP = "help";

    private final AccessibleSettings settings;
    private final JDKProjectManager jdkProjectManager;
    private final JDKTestProjectManager jdkTestProjectManager;
    private final JDKVersionManager jdkVersionManager;
    private final TaskVariantManager taskVariantManager;
    private   final PlatformManager platformManager;

    public GetterAPI(
            final AccessibleSettings settings,
            final JDKProjectManager jdkProjectManager,
            final JDKTestProjectManager jdkTestProjectManager,
            final JDKVersionManager jdkVersionManager,
            final TaskVariantManager taskVariantManager,
            final PlatformManager platformManager
    ) {
        this.settings = settings;
        this.jdkProjectManager = jdkProjectManager;
        this.jdkTestProjectManager = jdkTestProjectManager;
        this.jdkVersionManager = jdkVersionManager;
        this.taskVariantManager = taskVariantManager;
        this.platformManager = platformManager;
    }

    private Optional<String> extractParamValue(Map<String, List<String>> paramsMap, String param) {
        return Optional.ofNullable(paramsMap.get(param))
                .filter(list -> list.size() == 1)
                .map(list -> list.get(0));
    }


    private QueryHandler getJobsHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> paramsMap) throws StorageException, ManagementException {
                final Optional<String> allInJenkinsOpt = extractParamValue(paramsMap, "allJenkins");
                final Optional<String> allInOtoolOpt = extractParamValue(paramsMap, "allOtool");
                final Optional<String> urlParam = extractParamValue(paramsMap, "URL");
                final Optional<String> orphansOnJenkinsParam = extractParamValue(paramsMap, "orphansJenkins");
                final Optional<String> orphansOnOtoolParam = extractParamValue(paramsMap, "orphansOtool");
                final Optional<String> testParam = extractParamValue(paramsMap, "jdkTestProjects");
                final Optional<String> jdkParam = extractParamValue(paramsMap, "jdkProjects");
                final Optional<String> excludeParam = extractParamValue(paramsMap, "exclude");
                final Optional<String> includeParam = extractParamValue(paramsMap, "include");
                final Optional<String> projectParam = extractParamValue(paramsMap, "project");

                final String url = urlParam.orElse("http://hydra.brq.redhat.com:8080/job/");

                List<String> onJenkins = new ArrayList<>();
                List<String> testProjects = new ArrayList<>();
                List<String> jdkProjects = new ArrayList<>();

                if (allInJenkinsOpt.isPresent() || orphansOnJenkinsParam.isPresent() || orphansOnOtoolParam.isPresent()) {
                    try {
                       onJenkins = getAllJenkinsJobs();
                    } catch (Throwable e) {
                        throw new RuntimeException(e);
                    }
                }

                if (allInOtoolOpt.isPresent() || testParam.isPresent() || orphansOnJenkinsParam.isPresent() || orphansOnOtoolParam.isPresent()) {
                    testProjects = getAllJdkTestJobs(settings, jdkTestProjectManager, projectParam);
                }

                if (allInOtoolOpt.isPresent() || jdkParam.isPresent() || orphansOnJenkinsParam.isPresent() || orphansOnOtoolParam.isPresent()) {
                    jdkProjects = getAllJdkJobs(settings, jdkProjectManager, projectParam);
                }

                if (excludeParam.isPresent()) {
                    onJenkins = onJenkins.stream().filter(new BlacklistPredicate(excludeParam.get())).collect(Collectors.toList());
                    jdkProjects = jdkProjects.stream().filter(new BlacklistPredicate(excludeParam.get())).collect(Collectors.toList());
                    testProjects = testProjects.stream().filter(new BlacklistPredicate(excludeParam.get())).collect(Collectors.toList());
                }

                if (includeParam.isPresent()) {
                    onJenkins = onJenkins.stream().filter(new BlacklistPredicate(includeParam.get()).negate()).collect(Collectors.toList());
                    jdkProjects = jdkProjects.stream().filter(new BlacklistPredicate(includeParam.get()).negate()).collect(Collectors.toList());
                    testProjects = testProjects.stream().filter(new BlacklistPredicate(includeParam.get()).negate()).collect(Collectors.toList());
                }

                if (allInJenkinsOpt.isPresent()) {
                    return Result.ok(String.join("\n", onJenkins.stream().map(c -> url + c).collect(Collectors.toList()))+"\n");
                }
                if (jdkParam.isPresent()) {
                    return Result.ok(String.join("\n", jdkProjects.stream().map(c -> url + c).collect(Collectors.toList()))+"\n");
                }
                if (testParam.isPresent()) {
                    return Result.ok(String.join("\n", testProjects.stream().map(c -> url + c).collect(Collectors.toList()))+"\n");
                }
                if (allInOtoolOpt.isPresent()) {
                    return Result.ok(String.join("\n", Stream.concat(testProjects.stream(), jdkProjects.stream()).map(c -> url + c).collect(Collectors.toList()))+"\n");
                }
                if (orphansOnJenkinsParam.isPresent()) {
                    return Result.ok(String.join("\n", Stream.concat(testProjects.stream(), jdkProjects.stream()).filter(new RemoveIfFound(onJenkins)).collect(Collectors.toList()))+"\n");
                }
                if (orphansOnOtoolParam.isPresent()) {
                    return Result.ok(String.join("\n", onJenkins.stream().filter(new RemoveIfFound(Stream.concat(testProjects.stream(), jdkProjects.stream()).collect(Collectors.toList()))).collect(Collectors.toList()))+"\n");
                }
                return Result.err("Wrong/missing parameters");
            }

            @Override
            public String about() {
                return "/jobs?[one of:[" + String.join(
                        "|",
                        "allJenkins /*all jobs on jenkins*/", //all on jenkins
                        "orphansJenkins /*jobs missing on jenkins*/", //ison jenkins, not in otool
                        "orphansOtool /*jobs redundant on jekins*/", //is on otool, not in jenkins
                        "allOtool /*all jobs possible by curent setup of jenkins*/", //all otooled (comb of two below)
                        "jdkTestProjects",
                        "jdkProjects] + one times optionalls[",
                        "URL=<prefix>",
                        "exclude=<regex1>,<regex2>...",
                        "include=<regex1>,<regex2>...",
                        "project=<projectName>]"
                ) + "]";
            }
        };
    }

    private static boolean checkProjectName(String id, Optional<String> projectParam) {
        if (projectParam.isPresent()) {
            return id.equals(projectParam.get());
        } else {
            return true;
        }
    }

    public static List<String> getAllJdkTestJobs(AccessibleSettings settings, JDKTestProjectManager jdkTestProjectManager, Optional<String> projectFilter) throws StorageException, ManagementException {
        List<String> testProjects = new ArrayList<>();
        final JDKProjectParser jdkProjectParser = new JDKProjectParser(
                ConfigManager.create(settings.getConfigRoot().getAbsolutePath()),
                settings.getLocalReposRoot(),
                settings.getScriptsRoot()
        );
        for (final JDKTestProject jdkTestProject : jdkTestProjectManager.readAll()) {
            if (checkProjectName(jdkTestProject.getId(), projectFilter)) {
                Set<Job> testJobsSet = jdkProjectParser.parse(jdkTestProject);
                for (Job j : testJobsSet) {
                    testProjects.add(j.getName());
                }
            }
        }
        Collections.sort(testProjects);
        return testProjects;
    }

    public static List<String> getAllJdkJobs(AccessibleSettings settings, JDKProjectManager jdkProjectManager, Optional<String> projectFilter) throws StorageException, ManagementException {
        List<String> jdkProjects = new ArrayList<>();
        final JDKProjectParser jdkProjectParser = new JDKProjectParser(
                ConfigManager.create(settings.getConfigRoot().getAbsolutePath()),
                settings.getLocalReposRoot(),
                settings.getScriptsRoot()
        );
        for (final JDKProject jdkProject : jdkProjectManager.readAll()) {
            if (checkProjectName(jdkProject.getId(), projectFilter)) {
                Set<Job> tr = jdkProjectParser.parse(jdkProject);
                for (Job j : tr) {
                    jdkProjects.add(j.getName());
                }
            }
        }
        Collections.sort(jdkProjects);
        return jdkProjects;
    }


    public static List<String> getAllJenkinsJobs() throws Exception {
        try {
            List<String> onJenkins = new ArrayList<>();
            String[] tr = JenkinsCliWrapper.getCli().listJobsToArray();
            for (String s : tr) {
                onJenkins.add(s);
            }
            Collections.sort(onJenkins);
            return onJenkins;
        } catch (Throwable e) {
            throw new Exception(e);
        }
    }

    private QueryHandler getJDKVersionHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> paramsMap) throws StorageException, ManagementException {
                Optional<String> productOpt = extractParamValue(paramsMap, PRODUCT);
                if (productOpt.isPresent()) {
                    final String product = productOpt.get();
                    final List<JDKVersion> jdkVersions = jdkVersionManager.readAll();
                    final Optional<JDKVersion> optionalJDKVersion = jdkVersions.stream()
                            .filter(jdkVersion -> jdkVersion.getPackageNames().contains(product))
                            .findFirst();
                    if (optionalJDKVersion.isPresent()) {
                        final JDKVersion jdkVersion = optionalJDKVersion.get();
                        return Result.ok(jdkVersion.getId());
                    }
                }
                final Optional<String> projectOpt = extractParamValue(paramsMap, PROJECT);
                if (projectOpt.isPresent()) {
                    final String projectName = projectOpt.get();
                    final Project project;
                    if (jdkProjectManager.contains(projectName)) {
                        project = jdkProjectManager.read(projectName);
                    } else if (jdkTestProjectManager.contains(projectName)) {
                        project = jdkTestProjectManager.read(projectName);
                    } else {
                        return Result.err("Project " + projectName + " doesn't exist");
                    }
                    return Result.ok(project.getProduct().getJdk());
                }
                return Result.err("Wrong/missing parameters");
            }

            @Override
            public String about() {
                return "/jdkVersion?[" + String.join(
                        "|",
                        PRODUCT + "=<packageName>",
                        PROJECT + "=<projectName>"
                ) + "]";
            }
        };
    }

    private QueryHandler getJDKVersionsHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> paramsMap) throws StorageException {
                List<String> jdkVersions = jdkVersionManager.readAll()
                        .stream()
                        .map(JDKVersion::getId)
                        .sorted(String::compareTo)
                        .collect(Collectors.toList());
                return Result.ok(String.join("\n", jdkVersions));
            }

            @Override
            public String about() {
                return "/jdkVersions";
            }
        };
    }

    private QueryHandler getPortHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> paramsMap) {
                final Optional<String> serviceOpt = extractParamValue(paramsMap, SERVICE);
                if (serviceOpt.isPresent()) {
                    final String service = serviceOpt.get();
                    final int port;
                    switch (service) {
                        case XML_RPC:
                            port = settings.getXmlRpcPort();
                            break;
                        case FILE_DOWNLOAD:
                            port = settings.getFileDownloadPort();
                            break;
                        case SSH:
                            port = settings.getSshPort();
                            break;
                        case WEBAPP:
                            port = settings.getWebappPort();
                            break;
                        default:
                            return Result.err("Unknown service: " + service);
                    }
                    return Result.ok(String.valueOf(port));
                }
                return Result.err(ERROR_PARAMETERS_EXPECTED);
            }

            @Override
            public String about() {
                return "/port?service=[" + String.join(
                        "|",
                        SSH,
                        XML_RPC,
                        WEBAPP,
                        FILE_DOWNLOAD
                ) + "]";
            }
        };
    }

    private QueryHandler getProductHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) throws StorageException {
                final Optional<String> archiveOpt = extractParamValue(queryParams, ARCHIVE);
                if (archiveOpt.isPresent()) {
                    final String archive = archiveOpt.get();
                    return new OToolParser(
                            jdkProjectManager.readAll(),
                            jdkVersionManager.readAll(),
                            taskVariantManager.getBuildVariants()
                    )
                            .parseArchive(archive)
                            .map(OToolBuild::getPackageName);
                }
                final Optional<String> projectOpt = extractParamValue(queryParams, PROJECT);
                if (projectOpt.isPresent()) {
                    final String project = projectOpt.get();
                    final List<JDKProject> jdkProjects = jdkProjectManager.readAll();
                    final Optional<JDKProject> optionalJDKProject = jdkProjects.stream()
                            .filter(proj -> proj.getId().equals(project)).findFirst();
                    if (optionalJDKProject.isPresent()) {
                        final JDKProject jdkProject = optionalJDKProject.get();
                        return Result.ok(jdkProject.getProduct().getPackageName());

                    }
                    final List<JDKTestProject> jdkTestProjects = jdkTestProjectManager.readAll();
                    final Optional<JDKTestProject> optionalJDKTestProject = jdkTestProjects.stream()
                            .filter(proj -> proj.getId().equals(project)).findFirst();
                    if (optionalJDKTestProject.isPresent()) {
                        final JDKTestProject jdkTestProject = optionalJDKTestProject.get();
                        return Result.ok(jdkTestProject.getProduct().getPackageName());
                    }
                    return Result.err(ERROR_PROJECT_NOT_FOUND);
                }
                final Optional<String> buildOpt = extractParamValue(queryParams, BUILD);
                if (buildOpt.isPresent()) {
                    final String build = buildOpt.get();
                    return new OToolParser(
                            jdkProjectManager.readAll(),
                            jdkVersionManager.readAll(),
                            taskVariantManager.getBuildVariants()
                    )
                            .parseBuild(build)
                            .map(OToolBuild::getPackageName);
                }
                return Result.err(ERROR_PARAMETERS_EXPECTED);
            }

            @Override
            public String about() {
                return "/product?[" + String.join(
                        "|",
                        ARCHIVE + "=<NVRA>",
                        BUILD + "=<NVR>",
                        PROJECT + "=projectName"
                ) + "]";
            }
        };
    }

    private QueryHandler getProductsHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) throws StorageException {
                final List<String> allProducts = jdkVersionManager.readAll()
                        .stream()
                        .flatMap(jdkVersion -> jdkVersion.getPackageNames().stream())
                        .sorted(String::compareTo)
                        .collect(Collectors.toList());
                return Result.ok(String.join("\n", allProducts));
            }

            @Override
            public String about() {
                return "/products";
            }
        };
    }

    private QueryHandler getProjectHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) throws StorageException {
                final Optional<String> archiveOpt = extractParamValue(queryParams, ARCHIVE);
                final Optional<String> buildOpt = extractParamValue(queryParams, BUILD);
                final OToolParser parser = new OToolParser(
                        jdkProjectManager.readAll(),
                        jdkVersionManager.readAll(),
                        taskVariantManager.getBuildVariants()
                );

                if (archiveOpt.isPresent()) {
                    final String archive = archiveOpt.get();
                    return parser.parseArchive(archive).map(OToolArchive::getProjectName);
                }

                if (buildOpt.isPresent()) {
                    final String build = buildOpt.get();
                    return parser.parseBuild(build).map(OToolBuild::getProjectName);
                }
                return Result.err(ERROR_PARAMETERS_EXPECTED);
            }

            @Override
            public String about() {
                return "/project?[" + String.join(
                        "|",
                        ARCHIVE + "=<NVRA>",
                        BUILD + "=<NVR>"
                ) + "]";
            }
        };
    }

    private QueryHandler getProjectsHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) throws StorageException {
                final Optional<String> typeOpt = extractParamValue(queryParams, TYPE);
                if (typeOpt.isPresent()) {
                    final String type = typeOpt.get();
                    final Stream<Project> projects;
                    switch (Project.ProjectType.valueOf(type)) {
                        case JDK_PROJECT:
                            projects = jdkProjectManager.readAll()
                                    .stream()
                                    .map(project -> project);
                            break;
                        case JDK_TEST_PROJECT:
                            projects = jdkTestProjectManager.readAll()
                                    .stream()
                                    .map(project -> project);
                            break;
                        default:
                            return Result.err("Unknown project type");
                    }
                    return Result.ok(projects
                            .map(Project::getId)
                            .sorted(String::compareTo)
                            .collect(Collectors.joining("\n"))
                    );
                }
                final Stream<Project> projects = Stream.of(
                        jdkProjectManager.readAll(),
                        jdkTestProjectManager.readAll()
                ).flatMap(List::stream);
                Optional<String> productOpt = extractParamValue(queryParams, PRODUCT);
                if (productOpt.isPresent()) {
                    final String product = productOpt.get();
                    return Result.ok(projects
                            .filter(project -> project.getProduct().getPackageName().equals(product))
                            .map(Project::getId)
                            .collect(Collectors.joining("\n")));
                }
                return Result.ok(projects.map(Project::getId)
                        .sorted(String::compareTo)
                        .collect(Collectors.joining("\n")));
            }

            @Override
            public String about() {
                return "/projects?[" + String.join("|",
                        TYPE + "=[" + Project.ProjectType.JDK_PROJECT + "|"
                                + Project.ProjectType.JDK_TEST_PROJECT + "]") + "]";
            }
        };
    }

    private QueryHandler getPathHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) {
                final Optional<String> rootOpt = extractParamValue(queryParams, ROOT);
                if (rootOpt.isPresent()) {
                    final String root = rootOpt.get();
                    final String rootPath;
                    switch (root) {
                        case BUILDS:
                            rootPath = settings.getDbFileRoot().getAbsolutePath();
                            break;
                        case CONFIGS:
                            rootPath = settings.getConfigRoot().getAbsolutePath();
                            break;
                        case JENKINS_JOBS:
                            rootPath = settings.getJenkinsJobsRoot().getAbsolutePath();
                            break;
                        case JENKINS_JOB_ARCHIVE:
                            rootPath = settings.getJenkinsJobArchiveRoot().getAbsolutePath();
                            break;
                        case REPOS:
                            rootPath = settings.getLocalReposRoot().getAbsolutePath();
                            break;
                        default:
                            return Result.err("Unknown root: " + root);
                    }
                    return Result.ok(rootPath);
                }
                return Result.err(ERROR_PARAMETERS_EXPECTED);
            }

            @Override
            public String about() {
                return "/path?root=[" + String.join("|",
                        BUILDS, CONFIGS, JENKINS_JOBS, JENKINS_JOB_ARCHIVE, REPOS
                        ) + "]";
            }
        };
    }

    private QueryHandler getPlatformsHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) throws StorageException {
                List<Platform> platforms = platformManager.readAll();
                String kojiArches = platforms.stream()
                        .map(platform -> platform.toString("\n"))
                        .sorted()
                        .collect(Collectors.joining("\n"));
                return Result.ok(kojiArches + "\n");
            }

            @Override
            public String about() {
                return "/platforms";
            }
        };
    }

    private QueryHandler getKojiArchesHandler() {
        return new QueryHandler() {
            @Override
            public Result<String, String> handle(Map<String, List<String>> queryParams) throws StorageException {
                List<Platform> platforms = platformManager.readAll();
                String kojiArches = platforms.stream()
                        .map(platform -> platform.getKojiArch().orElse(platform.getArchitecture()))
                        .distinct()
                        .sorted()
                        .collect(Collectors.joining("\n"));

                return Result.ok(kojiArches + "\n");
            }

            @Override
            public String about() {
                return "/kojiArches";
            }
        };
    }

    private Map<String, QueryHandler> getHandlers() {
        return Collections.unmodifiableMap(new HashMap<String, QueryHandler>() {{
            put(JOBS, getJobsHandler());
            put(JDK_VERSION, getJDKVersionHandler());
            put(JDK_VERSIONS, getJDKVersionsHandler());
            put(PORT, getPortHandler());
            put(PRODUCTS, getProductsHandler());
            put(PRODUCT, getProductHandler());
            put(PROJECTS, getProjectsHandler());
            put(PROJECT, getProjectHandler());
            put(PATH, getPathHandler());
            put(PLATFORMS, getPlatformsHandler());
            put(KOJI_ARCHES, getKojiArchesHandler());
        }});
    }

    @Override
    public void addEndpoints() {
        final Map<String, QueryHandler> handlers = getHandlers();
        handlers.forEach((endpoint, handler) -> path(endpoint, () -> get(context -> {
            final Result<String, String> result = handler.handle(context.queryParamMap());
            if (result.isError()) {
                final String error = result.getError();
                context.result(error).status(400);
            } else {
                context.result(result.getValue());
            }
        })));
        get(HELP, context -> {
            final String help = handlers.entrySet()
                    .stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .map(entry -> entry.getValue().about())
                    .collect(Collectors.joining("\n\n"));
            context.result(help);
        });
    }

    private interface QueryHandler {

        Result<String, String> handle(
                Map<String, List<String>> queryParams
        ) throws StorageException, ManagementException;

        String about();
    }

    private class BlacklistPredicate implements Predicate<String> {
        List<Pattern> patterns = new ArrayList<>();

        public BlacklistPredicate(String s) {
            String[] q = s.split(",");
            for(String regex: q){
                patterns.add(Pattern.compile(regex));
            }
        }

        @Override
        public boolean test(String o) {
            for (Pattern p: patterns){
                if (p.matcher(o).matches()){
                    return false;
                }
            }
            return true;
        }
    }

    private class RemoveIfFound implements Predicate<String> {
        private final List<String> anotherList;

        public RemoveIfFound(List<String> anotherList) {
            this.anotherList=anotherList;
        }

        @Override
        public boolean test(String o) {
            for (String s: anotherList){
                if (Objects.equals(o, s)){
                    return false;
                }
            }
            return true;
        }
    }
}
