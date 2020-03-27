package org.fakekoji.api.http.rest;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.plugin.json.JavalinJackson;
import org.fakekoji.core.AccessibleSettings;
import org.fakekoji.core.utils.matrix.BuildEqualityFilter;
import org.fakekoji.core.utils.matrix.MatrixGenerator;
import org.fakekoji.core.utils.matrix.TestEqualityFilter;
import org.fakekoji.jobmanager.*;
import org.fakekoji.jobmanager.manager.*;
import org.fakekoji.jobmanager.model.JDKProject;
import org.fakekoji.jobmanager.model.JDKTestProject;
import org.fakekoji.jobmanager.model.JobUpdateResults;
import org.fakekoji.jobmanager.project.JDKProjectManager;
import org.fakekoji.jobmanager.project.JDKTestProjectManager;
import org.fakekoji.model.Platform;
import org.fakekoji.model.Task;
import org.fakekoji.storage.StorageException;
import org.fakekoji.xmlrpc.server.JavaServerConstants;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.path;
import static org.fakekoji.core.AccessibleSettings.objectMapper;

public class OToolService {

    private static final Logger LOGGER = Logger.getLogger(JavaServerConstants.FAKE_KOJI_LOGGER);

    private static final String ID = "id";
    private static final String CONFIG_ID = "/:" + ID;
    private static final String BUILD_PROVIDERS = "/buildProviders";
    private static final String BUILD_PROVIDER = BUILD_PROVIDERS + CONFIG_ID;
    private static final String JDK_VERSIONS = "/jdkVersions";
    private static final String JDK_VERSION = JDK_VERSIONS + CONFIG_ID;
    private static final String PLATFORMS = "/platforms";
    private static final String PLATFORM = PLATFORMS + CONFIG_ID;
    private static final String TASKS = "/tasks";
    private static final String TASK = TASKS + CONFIG_ID;
    private static final String TASK_VARIANTS = "/taskVariants";
    private static final String TASK_VARIANT = TASK_VARIANTS + CONFIG_ID;
    private static final String JDK_PROJECTS = "/jdkProjects";
    private static final String JDK_PROJECT = JDK_PROJECTS + CONFIG_ID;
    private static final String JDK_TEST_PROJECTS = "/jdkTestProjects";
    private static final String JDK_TEST_PROJECT = JDK_TEST_PROJECTS + CONFIG_ID;
    private static final String GET = "get";

    private static final String MISC = "misc";
    private static final String VIEWS = "views";
    private static final String VIEWS_LIST_OTOOL = "list";
    private static final String VIEWS_DETAILS= "details";
    private static final String VIEWS_MATCHES= "matches";
    private static final String VIEWS_MATCHES_JENKINS= "jenkins";
    private static final String VIEWS_XMLS= "xmls";
    private static final String VIEWS_CREATE = "create";
    private static final String VIEWS_REMOVE = "remove";
    private static final String VIEWS_UPDATE = "update";
    private static final String REGENERATE_ALL = "regenerateAll";
    private static final String MATRIX = "matrix";
    private static final String MATRIX_ORIENTATION = "orientation";
    private static final String MATRIX_BREGEX = "buildRegex";
    private static final String MATRIX_TREGEX = "testRegex";

    private final int port;
    private final Javalin app;
    private final JobUpdater jenkinsJobUpdater;

    private String getMiscHelp() {
        return ""
                + MISC + "/" + REGENERATE_ALL + "/" + JDK_TEST_PROJECTS + "\n"
                + MISC + "/" + REGENERATE_ALL + "/" + JDK_PROJECTS + "\n"
                + "                optional argument project=         \n"
                + "                to regenerate only single project  \n"
                + MISC + "/" + VIEWS + "/{" + VIEWS_LIST_OTOOL+","+VIEWS_DETAILS+","+VIEWS_XMLS+","+ VIEWS_CREATE+","+ VIEWS_REMOVE+","+ VIEWS_UPDATE+","+VIEWS_MATCHES+","+VIEWS_MATCHES_JENKINS+ "}\n"
                + "                will affect jenkins views\n"
                + MISC + "/" + MATRIX + "\n"
                + "  where parameters for matrix are (with defaults):\n"
                + "  " + MATRIX_ORIENTATION + "=1 " + MATRIX_BREGEX + "=.* " + MATRIX_TREGEX + "=.* \n"
                + "  " + "tos=true tarch=true tprovider=false tsuite=true tvars=false bos=true barch=true bprovider=false bproject=true bjdk=true bvars=false\n"
                + "  dropRows=true dropColumns=true \n";
    }

    public OToolService(AccessibleSettings settings) {
        this.port = settings.getWebappPort();
        JavalinJackson.configure(objectMapper);
        app = Javalin.create(config -> config
                .addStaticFiles("/webapp")
        );
        jenkinsJobUpdater = new JenkinsJobUpdater(settings);
        final ConfigManager configManager = ConfigManager.create(settings.getConfigRoot().getAbsolutePath());

        final OToolHandlerWrapper wrapper = oToolHandler -> context -> {
            try {
                oToolHandler.handle(context);
            } catch (ManagementException e) {
                LOGGER.log(Level.SEVERE, notNullMessage(e), e);
                context.status(400).result(notNullMessage(e));
            } catch (StorageException e) {
                LOGGER.log(Level.SEVERE, notNullMessage(e), e);
                context.status(500).result(notNullMessage(e));
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, notNullMessage(e), e);
                context.status(501).result(notNullMessage(e));
            }
        };

        app.routes(() -> {

            final JDKTestProjectManager jdkTestProjectManager = new JDKTestProjectManager(
                    configManager.getJdkTestProjectStorage(),
                    jenkinsJobUpdater
            );
            final JDKProjectManager jdkProjectManager = new JDKProjectManager(
                    configManager,
                    jenkinsJobUpdater,
                    settings.getLocalReposRoot(),
                    settings.getScriptsRoot()
            );
            final PlatformManager platformManager = new PlatformManager(configManager.getPlatformStorage(), jenkinsJobUpdater);
            final TaskManager taskManager = new TaskManager(configManager.getTaskStorage(), jenkinsJobUpdater);

            path(MISC, () -> {
                get("help", wrapper.wrap(context -> {
                    context.status(200).result(getMiscHelp());
                }));
                path(VIEWS, () -> {
                    try {
                        List<JDKTestProject> l1 = jdkTestProjectManager.readAll();
                        List<JDKProject> l2 = jdkProjectManager.readAll();
                        List<Platform> l3 = platformManager.readAll();
                        List<Task> l4 = taskManager.readAll();
                        List<String> projects = new ArrayList<>();
                        for (JDKTestProject p : l1) {
                            projects.add(p.getId());
                        }
                        for (JDKProject p : l2) {
                            projects.add(p.getId());
                        }
                        Set<String> ossesSet = new HashSet<>();
                        Set<String> ossesVersionedSet = new HashSet<>();
                        Set<String> archesSet = new HashSet<>();
                        for (Platform p : l3) {
                            ossesSet.add(p.getOs());
                            ossesVersionedSet.add(p.getOs() + p.getVersion());
                            archesSet.add(p.getArchitecture());
                        }
                        //jenkins will resort any way, however..
                        Collections.sort(projects);
                        Collections.sort(l4, new Comparator<Task>() {
                            @Override
                            public int compare(Task o1, Task o2) {
                                return o1.getId().compareTo(o2.getId());
                            }
                        });
                        Collections.sort(l3, new Comparator<Platform>() {
                            @Override
                            public int compare(Platform o1, Platform o2) {
                                return o1.getId().compareTo(o2.getId());
                            }
                        });
                        List<String> osses = new ArrayList<>(ossesSet);
                        List<String> ossesVersioned = new ArrayList<>(ossesVersionedSet);
                        List<String> arches = new ArrayList<>(archesSet);
                        Collections.sort(osses);
                        Collections.sort(ossesVersioned);
                        Collections.sort(arches);
                        List<List<String>> subArches = Arrays.asList(osses, ossesVersioned, arches);
                        List<JenkinsViewTemplateBuilder> jvt = new ArrayList<>();
                        jvt.add(JenkinsViewTemplateBuilder.getTaskTemplate("update", Optional.empty(), Optional.empty(), Optional.of(l3)));
                        jvt.add(JenkinsViewTemplateBuilder.getTaskTemplate("pull", Optional.empty(), Optional.empty(), Optional.of(l3)));
                        for (Task p : l4) {
                            jvt.add(JenkinsViewTemplateBuilder.getTaskTemplate(p.getId(), p.getViewColumnsAsOptional(), Optional.empty(), Optional.of(l3)));
                        }
                        for (String p : projects) {
                            jvt.add(JenkinsViewTemplateBuilder.getProjectTemplate(p, Optional.empty(), Optional.of(l3)));
                        }
                        for (Platform p : l3) {
                            jvt.add(JenkinsViewTemplateBuilder.getPlatformTemplate(p.getId(), l3));
                        }
                        for (Platform platform : l3) {
                            for (Task p : l4) {
                                jvt.add(JenkinsViewTemplateBuilder.getTaskTemplate(p.getId(), p.getViewColumnsAsOptional(), Optional.of(platform.getId()), Optional.of(l3)));
                            }
                            for (String p : projects) {
                                jvt.add(JenkinsViewTemplateBuilder.getProjectTemplate(p, Optional.of(platform.getId()), Optional.of(l3)));
                            }
                        }
                        for (List<String> subArch : subArches) {
                            for (String s : subArch) {
                                for (Task p : l4) {
                                    jvt.add(JenkinsViewTemplateBuilder.getTaskTemplate(p.getId(), p.getViewColumnsAsOptional(), Optional.of(s), Optional.of(l3)));
                                }
                                for (String p : projects) {
                                    jvt.add(JenkinsViewTemplateBuilder.getProjectTemplate(p, Optional.of(s), Optional.of(l3)));
                                }
                            }
                        }
                        get(VIEWS_LIST_OTOOL, wrapper.wrap(context -> {
                                    context.status(200).result(String.join("\n", jvt) + "\n");
                                }
                        ));
                        get(VIEWS_DETAILS, wrapper.wrap(context -> {
                                    List<String> jjobs = GetterAPI.getAllJenkinsJobs();
                                    Collections.sort(jjobs);
                                    List<String> jobs = GetterAPI.getAllJdkJobs(settings, jdkProjectManager, Optional.empty());
                                    jobs.addAll(GetterAPI.getAllJdkTestJobs(settings, jdkTestProjectManager, Optional.empty()));
                                    Collections.sort(jobs);
                                    StringBuilder sb = new StringBuilder();
                                    for (JenkinsViewTemplateBuilder j : jvt) {
                                        Pattern p = j.getRegex();
                                        int jobCounter = 0;
                                        for (String job : jobs) {
                                            if (((Pattern) p).matcher(job).matches()) {
                                                jobCounter++;
                                            }
                                        }
                                        int jjobCounter = 0;
                                        for (String jjob : jjobs) {
                                            if (((Pattern) p).matcher(jjob).matches()) {
                                                jjobCounter++;
                                            }
                                        }
                                        sb.append(j.getName() + " (" + jobCounter + ") (" + jjobCounter + ") " + j.getRegex() + "\n");
                                    }
                                    context.status(200).result(sb.toString());
                                }
                        ));
                        get(VIEWS_XMLS, wrapper.wrap(context -> {
                                    StringBuilder sb = new StringBuilder();
                                    for (JenkinsViewTemplateBuilder j : jvt) {
                                        sb.append("  ***  " + j.getName() + "  ***  \n");
                                        sb.append(j.expand() + "\n");
                                    }
                                    context.status(200).result(sb.toString());
                                }
                        ));
                        get(VIEWS_MATCHES, wrapper.wrap(context -> {
                                    List<String> jobs = GetterAPI.getAllJdkJobs(settings, jdkProjectManager, Optional.empty());
                                    jobs.addAll(GetterAPI.getAllJdkTestJobs(settings, jdkTestProjectManager, Optional.empty()));
                                    Collections.sort(jobs);
                                    StringBuilder sb = new StringBuilder();
                                    for (JenkinsViewTemplateBuilder j : jvt) {
                                        sb.append(j.getName() + "\n");
                                        Pattern p = j.getRegex();
                                        for (String job : jobs) {
                                            if (((Pattern) p).matcher(job).matches()) {
                                                sb.append("  " + job + "\n");
                                            }
                                        }
                                    }
                                    context.status(200).result(sb.toString());
                                }
                        ));
                        get(VIEWS_MATCHES_JENKINS, wrapper.wrap(context -> {
                                    List<String> jobs = GetterAPI.getAllJenkinsJobs();
                                    Collections.sort(jobs);
                                    StringBuilder sb = new StringBuilder();
                                    for (JenkinsViewTemplateBuilder j : jvt) {
                                        sb.append(j.getName() + "\n");
                                        Pattern p = j.getRegex();
                                        for (String job : jobs) {
                                            if (((Pattern) p).matcher(job).matches()) {
                                                sb.append("  " + job + "\n");
                                            }
                                        }
                                    }
                                    context.status(200).result(sb.toString());
                                }
                        ));
                    } catch (Exception ex) {
                        throw new RuntimeException((ex));
                    }
                });
                path(REGENERATE_ALL, () -> {
                    get(JDK_TEST_PROJECTS, wrapper.wrap(context -> {
                        String project = context.queryParam("project");
                        JobUpdateResults r1 = jdkTestProjectManager.regenerateAll(project);
                        context.status(200).json(r1);
                    }));
                    get(JDK_PROJECTS, wrapper.wrap(context -> {
                        String project = context.queryParam("project");
                        JobUpdateResults r2 = jdkProjectManager.regenerateAll(project);
                        context.status(200).json(r2);
                    }));
                });
                get(MATRIX, wrapper.wrap(context -> {
                    String trex = context.queryParam(MATRIX_TREGEX);
                    String brex = context.queryParam(MATRIX_BREGEX);
                    boolean tos = notNullBoolean(context, "tos", true);
                    boolean tarch = notNullBoolean(context, "tarch", true);
                    boolean tprovider = notNullBoolean(context, "tprovider", false);
                    boolean tsuite = notNullBoolean(context, "tsuite", true);
                    boolean tvars = notNullBoolean(context, "tvars", false);
                    boolean bos = notNullBoolean(context, "bos", true);
                    boolean barch = notNullBoolean(context, "barch", true);
                    boolean bprovider = notNullBoolean(context, "bprovider", false);
                    boolean bproject = notNullBoolean(context, "bproject", true);
                    boolean bjdk = notNullBoolean(context, "bjdk", true);
                    boolean bvars = notNullBoolean(context, "bvars", false);
                    boolean dropRows = notNullBoolean(context, "dropRows", true);
                    boolean dropColumns = notNullBoolean(context, "dropColumns", true);
                    TestEqualityFilter tf = new TestEqualityFilter(tos, tarch, tprovider, tsuite, tvars);
                    BuildEqualityFilter bf = new BuildEqualityFilter(bos, barch, bprovider, bproject, bjdk, bvars);
                    MatrixGenerator m = new MatrixGenerator(settings, configManager, trex, brex, tf, bf);
                    int orieantaion = 1;
                    if (context.queryParam(MATRIX_ORIENTATION) != null) {
                        orieantaion = Integer.valueOf(context.queryParam(MATRIX_ORIENTATION));
                    }
                    context.status(200).result(m.printMatrix(orieantaion, dropRows, dropColumns));
                }));

            });

            app.post(JDK_TEST_PROJECTS, wrapper.wrap(context -> {
                final JDKTestProject jdkTestProject = context.bodyValidator(JDKTestProject.class).get();
                final ManagementResult result = jdkTestProjectManager.create(jdkTestProject);
                context.status(200).json(result);
            }));
            app.get(JDK_TEST_PROJECTS, wrapper.wrap(context -> context.json(jdkTestProjectManager.readAll())));
            app.put(JDK_TEST_PROJECT, wrapper.wrap(context -> {
                final JDKTestProject jdkTestProject = context.bodyValidator(JDKTestProject.class).get();
                final String id = context.pathParam(ID);
                final ManagementResult result = jdkTestProjectManager.update(id, jdkTestProject);
                context.status(200).json(result);
            }));
            app.delete(JDK_TEST_PROJECT, wrapper.wrap(context -> {
                final String id = context.pathParam(ID);
                final ManagementResult result = jdkTestProjectManager.delete(id);
                context.status(200).json(result);
            }));

            final BuildProviderManager buildProviderManager = new BuildProviderManager(configManager.getBuildProviderStorage());
            app.get(BUILD_PROVIDERS, context -> context.json(buildProviderManager.readAll()));

            final JDKVersionManager jdkVersionManager = new JDKVersionManager(configManager.getJdkVersionStorage());
            app.get(JDK_VERSIONS, context -> context.json(jdkVersionManager.readAll()));

            app.get(PLATFORMS, context -> context.json(platformManager.readAll()));
            app.post(PLATFORMS, context -> {
                try {
                    final Platform platform = context.bodyValidator(Platform.class).get();
                    final ManagementResult<Platform> result = platformManager.create(platform);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            app.put(PLATFORM, context -> {
                try {
                    final String id = context.pathParam(ID);
                    final Platform platform = context.bodyValidator(Platform.class).get();
                    final ManagementResult<Platform> result = platformManager.update(id, platform);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            app.delete(PLATFORM, context -> {
                try {
                    final String id = context.pathParam(ID);
                    final ManagementResult<Platform> result = platformManager.delete(id);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });

            app.post(TASKS, context -> {
                try {
                    final Task task = context.bodyValidator(Task.class).get();
                    final ManagementResult<Task> result = taskManager.create(task);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            app.get(TASKS, context -> context.json(taskManager.readAll()));
            app.put(TASK, context -> {
                try {
                    final String id = context.pathParam(ID);
                    final Task task = context.bodyValidator(Task.class).get();
                    final ManagementResult<Task> result = taskManager.update(id, task);
                    context.json(result).status(200);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            app.delete(TASK, context -> {
                try {
                    final String id = context.pathParam(ID);
                    final ManagementResult<Task> result = taskManager.delete(id);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });

            final TaskVariantManager taskVariantManager = new TaskVariantManager(configManager.getTaskVariantStorage());
            app.get(TASK_VARIANTS, context -> context.json(taskVariantManager.readAll()));

            app.post(JDK_PROJECTS, context -> {
                try {
                    final JDKProject jdkProject = context.bodyValidator(JDKProject.class).get();
                    final ManagementResult<JDKProject> result = jdkProjectManager.create(jdkProject);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            app.get(JDK_PROJECTS, context -> context.json(jdkProjectManager.readAll()));
            app.put(JDK_PROJECT, context -> {
                try {
                    final JDKProject jdkProject = context.bodyValidator(JDKProject.class).get();
                    final String id = context.pathParam(ID);
                    final ManagementResult<JDKProject> result = jdkProjectManager.update(id, jdkProject);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            app.delete(JDK_PROJECT, context -> {
                try {
                    final String id = context.pathParam(ID);
                    final ManagementResult<JDKProject> result = jdkProjectManager.delete(id);
                    context.status(200).json(result);
                } catch (ManagementException e) {
                    context.status(400).result(e.toString());
                } catch (StorageException e) {
                    context.status(500).result(e.toString());
                }
            });
            path(GET, new GetterAPI(
                    settings,
                    jdkProjectManager,
                    jdkTestProjectManager,
                    jdkVersionManager,
                    taskVariantManager
            ));
        });
    }

    private String notNullMessage(Exception e) {
        if (e == null) {
            return "exception witout exception. Good luck logger.";
        } else {
            if (e.getMessage() == null) {
                return "Exception was thrown, but no message was left. Pray for trace.";
            } else {
                return e.getMessage();
            }
        }
    }

    private boolean notNullBoolean(Context context, String key, boolean defoult) {
        if (context.queryParam(key) == null) {
            return defoult;
        } else {
            return Boolean.valueOf(context.queryParam(key));
        }
    }


    public void start() {
        app.start(port);
    }

    public void stop() {
        app.stop();
    }

    public int getPort() {
        return port;
    }

    interface OToolHandler {
        void handle(Context context) throws Exception;
    }

    interface OToolHandlerWrapper {
        Handler wrap(OToolHandler oToolHandler);
    }
}
