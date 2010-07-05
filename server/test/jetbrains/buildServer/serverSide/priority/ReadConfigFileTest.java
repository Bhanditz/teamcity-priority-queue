/*
 * Copyright 2000-2010 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.serverSide.priority;

import jetbrains.buildServer.TestLogger;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.impl.FileWatcherFactory;
import jetbrains.buildServer.util.EventDispatcher;
import jetbrains.buildServer.util.FileUtil;
import org.apache.log4j.Level;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.lib.legacy.ClassImposteriser;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static jetbrains.buildServer.serverSide.priority.Util.getTestDataDir;
import static jetbrains.buildServer.serverSide.priority.Util.prepareBuildTypes;
import static org.testng.AssertJUnit.*;

/**
 * @author dmitry.neverov
 */
@Test
public class ReadConfigFileTest {

  @AfterMethod(alwaysRun = true)
  public void tearDown() throws InterruptedException {
    FileUtil.delete(new File(getTestDataDir(), PriorityClassManagerImpl.PRIORITY_CLASS_CONFIG_FILENAME));
  }

  @Test
  public void test() throws IOException {
    PriorityClassManager priorityClassManager = createPriorityClassManagerForConfig(new File(getTestDataDir(), "build-queue-priorities-sample.xml"));
    PriorityClass pc1 = priorityClassManager.findPriorityClassById("pc1");
    assertNotNull(pc1);
    assertEquals("pc1", pc1.getId());
    assertEquals("Inspections", pc1.getName());
    assertEquals("Low priority inspections", pc1.getDescription());
    assertEquals(-1, pc1.getPriority());
    assertEquals(2, pc1.getBuildTypes().size());
    Set<String> btIds1 = new HashSet<String>();
    for (SBuildType bt: pc1.getBuildTypes()) {
      btIds1.add(bt.getBuildTypeId());
    }
    assertTrue(btIds1.contains("bt14"));
    assertTrue(btIds1.contains("bt47"));

    PriorityClass pc2 = priorityClassManager.findPriorityClassById("pc2");
    assertNotNull(pc2);
    assertEquals("pc2", pc2.getId());
    assertEquals("Release", pc2.getName());
    assertEquals("Soon to be released", pc2.getDescription());
    assertEquals(5, pc2.getPriority());
    assertEquals(2, pc2.getBuildTypes().size());
    Set<String> btIds2 = new HashSet<String>();
    for (SBuildType bt: pc2.getBuildTypes()) {
      btIds2.add(bt.getBuildTypeId());
    }
    assertTrue(btIds2.contains("bt1"));
    assertTrue(btIds2.contains("bt3"));


    PriorityClass pc3 = priorityClassManager.createPriorityClass("New Priority Class", "", 0);
    assertEquals("priorityClassIdSequence was not corrected by config", "pc3", pc3.getId());
  }

  @DataProvider(name = "priorityConfigs")
  public Object[][] priorityConfigs() {
    return new Object[][] {
            {new File(getTestDataDir(), "build-queue-priorities-empty.xml")},
            {null},
            {new File(getTestDataDir(), "build-queue-priorities-invalid.xml")},
            {new File(getTestDataDir(), "build-queue-priorities-with-default.xml")}
    };
  }

  @Test(dataProvider = "priorityConfigs")
  public void test_server_startup(File prioritiesConfig) throws IOException {
    createPriorityClassManagerForConfig(prioritiesConfig);
  }


  @Test
  public void test_priority_classes_not_deleted_after_read_invalid_config() throws IOException {
    PriorityClassManager priorityClassManager = createPriorityClassManagerForConfig(null);

    PriorityClass inspections = priorityClassManager.createPriorityClass("Inspections", "", -1);
    PriorityClass duplicates = priorityClassManager.createPriorityClass("Duplicates", "", -2);

    PriorityClass personals = priorityClassManager.getPersonalPriorityClass();
    PriorityClassImpl updatePersonals = new PriorityClassImpl(personals.getId(), personals.getName(), personals.getDescription(),
            5, personals.getBuildTypes());
    priorityClassManager.savePriorityClass(updatePersonals);

    //read invalid config
    FileUtil.copy(new File(getTestDataDir(), "build-queue-priorities-invalid.xml"),
            new File(getTestDataDir(), PriorityClassManagerImpl.PRIORITY_CLASS_CONFIG_FILENAME));
    ((PriorityClassManagerImpl)priorityClassManager).loadPriorityClasses();

    inspections = priorityClassManager.findPriorityClassByName("Inspections");
    assertNotNull(inspections);

    duplicates = priorityClassManager.findPriorityClassByName("Duplicates");
    assertNotNull(duplicates);

    assertNotNull(priorityClassManager.getDefaultPriorityClass());
    assertNotNull(priorityClassManager.getPersonalPriorityClass());
  }


  public void test_can_change_only_priority_of_personal_priority_class() throws IOException {
    PriorityClassManager priorityClassManager = createPriorityClassManagerForConfig(null);

    PriorityClass personal = priorityClassManager.getPersonalPriorityClass();
    String personalName = personal.getName();
    String personalDescription = personal.getDescription();
    List<SBuildType> personalBuildTypes = personal.getBuildTypes();

    FileUtil.copy(new File(getTestDataDir(), "build-queue-priorities-with-personal.xml"),
            new File(getTestDataDir(), PriorityClassManagerImpl.PRIORITY_CLASS_CONFIG_FILENAME));
    ((PriorityClassManagerImpl)priorityClassManager).loadPriorityClasses();

    personal = priorityClassManager.getPersonalPriorityClass();
    assertEquals(personalName, personal.getName());
    assertEquals(personalDescription, personal.getDescription());
    assertEquals(personalBuildTypes, personal.getBuildTypes());
    assertEquals(10, personal.getPriority());
  }


  private PriorityClassManager createPriorityClassManagerForConfig(File prioritiesConfig) throws IOException {
    if (prioritiesConfig != null) {
      FileUtil.copy(prioritiesConfig, new File(getTestDataDir(), PriorityClassManagerImpl.PRIORITY_CLASS_CONFIG_FILENAME));
    } else {
      FileUtil.delete(new File(getTestDataDir(), PriorityClassManagerImpl.PRIORITY_CLASS_CONFIG_FILENAME));
    }
    new TestLogger().onSuiteStart();

    Mockery context = new Mockery(){{
      setImposteriser(ClassImposteriser.INSTANCE);
    }};
    final SBuildServer server = context.mock(SBuildServer.class);
    final ServerPaths serverPaths = context.mock(ServerPaths.class);
    final EventDispatcher<BuildServerListener> eventDispatcher = (EventDispatcher<BuildServerListener>) context.mock(EventDispatcher.class);
    final BuildQueueEx queue = context.mock(BuildQueueEx.class);
    final ProjectManager projectManager = context.mock(ProjectManager.class);

    Loggers.SERVER.setLevel(Level.DEBUG);
    Map<String, SBuildType> id2buildType = prepareBuildTypes(context, projectManager, "bt14", "bt47", "bt1", "bt3", "bt5");

    context.checking(new Expectations() {{
      allowing(server).getQueue(); will(returnValue(queue));
      allowing(server).getFullServerVersion(); will(returnValue("1.0"));
      allowing(server).getProjectManager(); will(returnValue(projectManager));
      allowing(queue).setOrderingStrategy(with(any(BuildQueueOrderingStrategy.class)));
      allowing(queue).getItems(); will(returnValue(Collections.<Object>emptyList()));
      allowing(serverPaths).getConfigDir(); will(returnValue(getTestDataDir().getAbsolutePath()));
      allowing(eventDispatcher).addListener(with(any(BuildServerListener.class)));
    }});

    PriorityClassManagerImpl priorityClassManager = new PriorityClassManagerImpl(server, serverPaths, eventDispatcher, new FileWatcherFactory(serverPaths));
    BuildQueuePriorityOrdering strategy = new BuildQueuePriorityOrdering(priorityClassManager);
    ServerListener listener = new ServerListener(eventDispatcher, server, strategy, priorityClassManager);
    listener.serverStartup();

    PriorityClass defaultPriorityClass = priorityClassManager.getDefaultPriorityClass();
    assertNotNull(defaultPriorityClass);
    assertEquals(0, defaultPriorityClass.getPriority());

    PriorityClass personal = priorityClassManager.getPersonalPriorityClass();
    assertNotNull(personal);

    //buildType not in any priorityClass get default priority - 0
    assertEquals(0, priorityClassManager.getBuildTypePriorityClass(id2buildType.get("bt5")).getPriority());

    return priorityClassManager;
  }
}
