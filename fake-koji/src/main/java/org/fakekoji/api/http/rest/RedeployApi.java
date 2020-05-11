package org.fakekoji.api.http.rest;

import hudson.plugins.scm.koji.Constants;
import hudson.plugins.scm.koji.model.Build;
import io.javalin.apibuilder.EndpointGroup;
import org.fakekoji.Utils;
import org.fakekoji.core.AccessibleSettings;
import org.fakekoji.core.FakeBuild;
import org.fakekoji.core.utils.OToolParser;
import org.fakekoji.jobmanager.ManagementException;
import org.fakekoji.jobmanager.manager.PlatformManager;
import org.fakekoji.jobmanager.model.BuildJob;
import org.fakekoji.jobmanager.model.Job;
import org.fakekoji.jobmanager.model.Project;
import org.fakekoji.jobmanager.model.TestJob;
import org.fakekoji.jobmanager.project.JDKProjectManager;
import org.fakekoji.jobmanager.project.JDKProjectParser;
import org.fakekoji.jobmanager.project.JDKTestProjectManager;
import org.fakekoji.model.Platform;
import org.fakekoji.storage.StorageException;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


import static io.javalin.apibuilder.ApiBuilder.get;
import static org.fakekoji.api.http.rest.OToolService.MISC;

public class RedeployApi implements EndpointGroup {

    public static final String REDEPLOY = "re";
    //without list/do just list list waht can be done?
    //eg list of nvras in processed.txt for test
    //eg list new api content of fake koji for build?
    //already filtered via other filters?
    private static final String REDEPLOY_TEST = "test"; //test have sense with all the swithces before, build do not. build needs only 1/2 nvra
    private static final String REDEPLOY_BUILD = "build"; //only jdkProject in addtion to removal VR from processed.txt, it cleans  FAILED, ERROR and smaller then 4bytes files from local-builds
    private static final String REDEPLOY_DO = "do"; //will do the real work if true, by default it will only print what it will affect
    //with?
    private static final String REDEPLOY_NVR = "nvr"; //and enforce platform as separate thing?
    //all can be coma separated?
    private static final String REDEPLOY_TYPE = "type"; //jdkProject | jdkTestProject
    private static final String REDEPLOY_PROJECT = "project";
    //other details for selection
    private static final String REDEPLOY_os = "os";
    private static final String REDEPLOY_arch = "arch";
    private static final String REDEPLOY_version = "version";
    private static final String REDEPLOY_jp = "jp";
    private static final String REDEPLOY_task = "task";
    private static final String REDEPLOY_variants = "variants"; //coma separated list of test variants
    private static final String REDEPLOY_provider = "provider"; //coma separated? list ?of test providers?
    //sometimes we need also build arch to judge
    private static final String REDEPLOY_bos = "bos";
    private static final String REDEPLOY_barch = "barch";
    private static final String REDEPLOY_bversion = "bversion";
    private static final String REDEPLOY_bvariants = "bvariants"; //coma separated list of build variants; for build we do not care about provider?
    private static final String REDEPLOY_bprovider = "bprovider"; //coma separated? list ?of test providers? bprovider is irrelevant for tests

    private static final String ARCHES_EXPECTED = "archesExpected";
    private static final String ARCHES_EXPECTED_SET = "set";

    //is needed at the end?
    private static final String REDEPLOY_regex = "regex";


    private final JDKProjectParser parser;
    private final JDKProjectManager jdkProjectManager;
    private final JDKTestProjectManager jdkTestProjectManager;
    private final AccessibleSettings settings;
    private final PlatformManager platformManager;

    RedeployApi(
            final JDKProjectParser jdkProjectParser,
            final JDKProjectManager jdkProjectManager,
            final JDKTestProjectManager jdkTestProjectManager,
            final PlatformManager platformManager,
            final AccessibleSettings settings
    ) {
        this.parser = jdkProjectParser;
        this.jdkProjectManager = jdkProjectManager;
        this.jdkTestProjectManager = jdkTestProjectManager;
        this.platformManager = platformManager;
        this.settings = settings;
    }


    public static String getHelp() {
        return "\n"
                + MISC + '/' + REDEPLOY + "/" + REDEPLOY_BUILD + "\n"
                + "Will print out all nvrs in processed.txt of builds.\n"
                + MISC + '/' + REDEPLOY + "/" + REDEPLOY_TEST + "\n"
                + "Will print out all nvrs in processed.txt of tests.\n"
                + "once you set " + REDEPLOY_NVR + "=nvr the jobs affected by this nvr will be printed.\n"
                + "once you set " + REDEPLOY_DO + "=true, the real work will happen - nvr will be removed from affected jobs.\n"
                + "For " + REDEPLOY_BUILD + " it also removes the affected NVRA from database. A is deducted  from other params\n"
                +"\n"
                + MISC + '/'+ REDEPLOY+ '/' + ARCHES_EXPECTED + " will list exisiting " + FakeBuild.archesConfigFileName + " files"
                + "    with " + REDEPLOY_NVR + "  it shows arches expectewd of this NVR (dont forget, that in old api R contan also OS!\n"
                + "    with " + ARCHES_EXPECTED_SET + " and " + REDEPLOY_NVR + "  set arches  for  this NVR\n"
                + "       To set more global arches, you must go via file system, as we have new api, dont do that\n";


    }


    @Override
    public void addEndpoints() {
        get(REDEPLOY_BUILD, context -> {
            try {
                RedeployApiWorker raw = new RedeployApiWorker();
                raw.prepare(BuildJob.class);
                String nvr = context.queryParam(REDEPLOY_NVR);
                if (nvr == null) {
                    context.status(OToolService.OK).result(String.join("\n", raw.sortedNvrs) + "\n");
                } else {
                    //filtr affected jobs + places in builds (s neb arches>
                    context.status(OToolService.OK).result(raw.allRelevantJobsMap.values().stream().map(Job::getName).sorted().collect(Collectors.joining("\n")) + "\n");
                    //better to filter them up rather then here
                }
            } catch (StorageException | ManagementException | IOException e) {
                context.status(400).result(e.getMessage());
            } catch (Exception e) {
                context.status(500).result(e.getMessage());
            }
        });
        get(REDEPLOY_TEST, context -> {
            try {
                RedeployApiWorker raw = new RedeployApiWorker();
                raw.prepare(TestJob.class);
                String nvr = context.queryParam(REDEPLOY_NVR);
                if (nvr == null) {
                    context.status(OToolService.OK).result(String.join("\n", raw.sortedNvrs) + "\n");
                } else {
                    //filtr affected jobs + places in builds (s neb arches>
                    context.status(OToolService.OK).result(raw.allRelevantJobsMap.values().stream().map(Job::getName).sorted().collect(Collectors.joining("\n")) + "\n");
                    //better to filter them up rather then here
                }
            } catch (StorageException | ManagementException | IOException e) {
                context.status(400).result(e.getMessage());
            } catch (Exception e) {
                context.status(500).result(e.getMessage());
            }
        });
        get(ARCHES_EXPECTED, context -> {
            try {
                String nvr = context.queryParam(REDEPLOY_NVR);
                if (nvr != null) {
                    OToolParser.LegacyNVR nvrParsed = new OToolParser.LegacyNVR(nvr);
                    String set = context.queryParam(ARCHES_EXPECTED_SET);
                    if (set == null) {
                        File f = new File(settings.getDbFileRoot().toPath().toFile().getAbsolutePath() + "/" + nvrParsed.getFullPath() + "/data");
                        while (f.getParentFile() != null) {
                            File ae = new File(f, FakeBuild.archesConfigFileName);
                            if (ae.exists()) {
                                break;
                            }
                            f = f.getParentFile();
                        }
                        if (f.getParentFile() == null) {
                            context.status(OToolService.OK).result("No " + FakeBuild.archesConfigFileName + " for " + nvr + " arches expected are old api only!\n");
                        } else {
                            File ae = new File(f, FakeBuild.archesConfigFileName);
                            //by luck, same comments schema is used in processed.txt and arches.expected
                            List<String> archesWithoutCommenrs = Utils.readProcessedTxt(ae);
                            if (ae.length() < 4 || archesWithoutCommenrs.isEmpty()) {
                                context.status(OToolService.BAD).result(ae.getAbsolutePath() + " is emmpty or very small. That will break a lot of stuff!\n");
                            } else {
                                context.status(OToolService.OK).result(String.join(" ", archesWithoutCommenrs.get(0)) + " (" + ae.getAbsolutePath() + ")\n");
                            }
                        }
                    } else {
                        File mainPath = new File(settings.getDbFileRoot().toPath().toFile().getAbsolutePath() + "/" + nvrParsed.getFullPath());
                        if (!mainPath.exists()) {
                            context.status(OToolService.BAD).result(mainPath.getAbsolutePath() + " Do not exists. Invlid NVRos?\n");
                        } else {
                            File data = new File(mainPath, "data");
                            File mainFile = new File(data, FakeBuild.archesConfigFileName);
                            if (mainFile.exists()) {
                                List<String> archesWithoutCommenrs = Utils.readProcessedTxt(mainFile);
                                FakeBuild.generateDefaultArchesFile(mainFile, set);
                                if (archesWithoutCommenrs.isEmpty()) {
                                    context.status(OToolService.OK).result("overwritten " + mainFile + " (was: empty, is`" + set + "`)\n");
                                } else {
                                    context.status(OToolService.OK).result("overwritten " + mainFile + " (was: `" + archesWithoutCommenrs.get(0) + "`, is`" + set + "`)\n");
                                }

                            } else {
                                data.mkdirs();
                                FakeBuild.generateDefaultArchesFile(mainFile, set);
                                context.status(OToolService.OK).result("written " + mainFile + "  (is`" + set + "`)\n");
                            }
                        }
                    }
                } else {
                    ArchesExpectedWorker aw = new ArchesExpectedWorker();
                    aw.prepare();
                    List<Platform> platforms = platformManager.readAll();
                    Set<String> kojiArches = new HashSet<>();
                    for (Platform p : platforms) {
                        kojiArches.add(p.getKojiArch().orElse(p.getArchitecture()));
                    }
                    //filtr affected jobs + places in builds (s neb arches>
                    context.status(OToolService.OK).result(aw.dirsWithArches.entrySet().stream().
                            map((Function<Map.Entry<String, String[]>, String>) e -> String.join(" ", e.getValue()) + " ("+e.getKey()+")").
                            sorted().
                            collect(Collectors.joining("\n")) + "\n" +
                            "All used: " + aw.usedArches.stream().
                            collect(Collectors.joining(" ")) + "\n" +
                            "All possible: " + kojiArches.stream().
                            collect(Collectors.joining(" ")) + "\n");

                    //better to filter them up rather then here
                }
            } catch (StorageException | IOException e) {
                context.status(400).result(e.getMessage());
            } catch (Exception e) {
                context.status(500).result(e.getMessage());
            }
        });
    }

    private class ArchesExpectedWorker {

        private final Map<String, String[]> dirsWithArches = new HashMap<>();
        private final Set<String> usedArches = new HashSet<>();
        private boolean called = false;

        public void prepare() throws IOException {
            if (called) {
                throw new IOException("No need to call tis twice");
            }
            called = true;
            Files.walkFileTree(settings.getDbFileRoot().toPath(), new FileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    File f = file.toFile();
                    if (f.getName().equals(FakeBuild.archesConfigFileName)) {
                        //by luck, same comments schema is used in processed.txt and arches.expected
                        List<String> archesWithoutCommenrs = Utils.readProcessedTxt(f);
                        if (archesWithoutCommenrs.size() > 0 || f.length() <= 4) {
                            dirsWithArches.put(f.getParentFile().getAbsolutePath(), archesWithoutCommenrs.get(0).split("\\s+")); //also usage of this file takes first found, valid line
                            usedArches.addAll(Arrays.asList(archesWithoutCommenrs.get(0).split("\\s+")));
                        } else {
                            dirsWithArches.put(f.getParentFile().getAbsolutePath(), new String[]{"invalid"});
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });

        }
    }

    private class RedeployApiWorker {
        final Set<String> nvrsInProcessedTxt = new HashSet();
        final Map<String, Job> allRelevantJobsMap = new HashMap<>();
        final Map<String, List<String>> nvrsPerJob = new HashMap<>();
        final List<String> sortedNvrs = new ArrayList();
        private boolean called = false;

        public void prepare(Class clazz) throws StorageException, IOException, ManagementException {
            if (called) {
                throw new IOException("No need to call tis twice");
            }
            called = true;
            List<Project> allProjects = new ArrayList<>();
            //todo do not parse everything always
            // eg for builds just jdk projects ar enough and so on
            allProjects.addAll(jdkProjectManager.readAll());
            allProjects.addAll(jdkTestProjectManager.readAll());
            for (Project project : allProjects) {
                Set<Job> jobs = parser.parse(project);
                for (Job job : jobs) {
                    //filtr the other specifiers here
                    if (clazz.isInstance(job)) {
                        allRelevantJobsMap.put(job.getName(), job);
                        File processed = new File(new File(settings.getJenkinsJobsRoot(), job.getName()), Constants.PROCESSED_BUILDS_HISTORY);
                        if (processed.exists()) {
                            List<String> l = Utils.readProcessedTxt(processed);
                            nvrsPerJob.put(job.getName(), l);
                            nvrsInProcessedTxt.addAll(l);
                        }
                    }
                }
            }
            sortedNvrs.addAll(nvrsInProcessedTxt);
            Collections.sort(sortedNvrs);
        }
    }
}



