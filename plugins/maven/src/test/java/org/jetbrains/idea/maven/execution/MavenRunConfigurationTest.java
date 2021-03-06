/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package org.jetbrains.idea.maven.execution;

import com.intellij.testFramework.IdeaTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Element;

import java.util.Arrays;

public class MavenRunConfigurationTest extends IdeaTestCase {
  public void testSaveLoadRunnerParameters() {
    MavenRunConfiguration.MavenSettings s = new MavenRunConfiguration.MavenSettings(myProject);
    s.myRunnerParameters.setWorkingDirPath("some path");
    s.myRunnerParameters.setGoals(Arrays.asList("clean validate"));
    s.myRunnerParameters.setProfiles(Arrays.asList("prof1 prof2"));

    Element xml = XmlSerializer.serialize(s);
    MavenRunConfiguration.MavenSettings loaded
      = XmlSerializer.deserialize(xml, MavenRunConfiguration.MavenSettings.class);
    
    assertEquals(s.myRunnerParameters.getWorkingDirPath(), loaded.myRunnerParameters.getWorkingDirPath());
    assertEquals(s.myRunnerParameters.getGoals(), loaded.myRunnerParameters.getGoals());
    assertEquals(s.myRunnerParameters.getProfiles(), loaded.myRunnerParameters.getProfiles());
  }
}
