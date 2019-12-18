package com.breinjhel;

import java.io.File;
import java.io.FileFilter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.breinjhel.model.TestSuite;

import br.eti.kinoshita.testlinkjavaapi.TestLinkAPI;
import br.eti.kinoshita.testlinkjavaapi.constants.ExecutionStatus;
import br.eti.kinoshita.testlinkjavaapi.constants.ExecutionType;
import br.eti.kinoshita.testlinkjavaapi.constants.TestCaseDetails;
import br.eti.kinoshita.testlinkjavaapi.model.Build;
import br.eti.kinoshita.testlinkjavaapi.model.TestCase;
import br.eti.kinoshita.testlinkjavaapi.model.TestPlan;
import br.eti.kinoshita.testlinkjavaapi.model.TestProject;

/**
 * Created by breinjhel on 8/11/16.
 */
@Mojo(name = "report-result")
public class TestlinkMojo extends AbstractMojo {

	@Parameter
	private String url;

	@Parameter
	private String devKey;

	@Parameter
	private String projectName;

	@Parameter
	private String testPlan;

	@Parameter
	private String platform;

	@Parameter(property = "testlink.build")
	private String build;

	@Parameter
	private String outputDir;

	@Parameter
	private String customField;

	@Parameter(defaultValue = "TEST-*.xml")
	private String filename;

	TestLinkAPI api;
	TestProject testProject;
	TestPlan tp;

	@Override
	public void execute() throws MojoExecutionException {
		try {
			this.api = new TestLinkAPI(new URL(this.url), this.devKey);
		} catch (final MalformedURLException e) {
			e.printStackTrace();
		}

		// Get all testcases in testlink that has custom and automated
		this.testProject = this.api.getTestProjectByName(this.projectName);
		this.tp = this.api.getTestPlanByName(this.testPlan, this.testProject.getName());
		List<TestCase> tcs;

		getLog().info("TestProject: " + this.testProject.getName());
		getLog().info("TestPlan: " + this.tp.getName());

		if (this.platform != null) {
			tcs = getTestcasesByPlatform(this.tp, this.platform);
		} else {
			final TestCase[] testCases = this.api.getTestCasesForTestPlan(this.tp.getId(), null, null, null, null, null, null, null,
					ExecutionType.AUTOMATED, null, TestCaseDetails.FULL);
			tcs = Arrays.asList(testCases);
		}

		final Map<String, List<TestCase>> testcasesPerCustomfields = sortTestcasesByCustomFields(tcs);
		// Parse xml junit result
		reportJunitResultsToTestlink(testcasesPerCustomfields);

	}

	private Map<String, List<TestCase>> sortTestcasesByCustomFields(List<TestCase> a) {
		final Map<String, List<TestCase>> b = new HashMap<>();

		final List<String> foundKeys = new ArrayList<>();

		// Get all keys
		a.stream().forEach(testCase -> {
			final String key = this.api.getTestCaseCustomFieldDesignValue(testCase.getId(), null, testCase.getVersion(),
					this.testProject.getId(), this.customField, null).getValue();
			if (key != null && !foundKeys.contains(key)) {
				foundKeys.add(key);
				b.put(key, new ArrayList<>());
			}
			b.get(key).add(testCase);
		});

		getLog().info("Found TestCases:");
		if (b.keySet().isEmpty()) {
			getLog().info("No TestCases found.");
		} else {
			b.keySet().stream().forEach(getLog()::info);
		}
		return b;

	}

	private List<TestCase> getTestcasesByPlatform(TestPlan tp, String platform) {
		final TestCase[] testCases = this.api.getTestCasesForTestPlan(tp.getId(), null, null, null, null, null, null, null,
				ExecutionType.AUTOMATED, null, TestCaseDetails.FULL);

		final List<TestCase> s = Arrays.stream(testCases).filter(a -> a.getPlatform().getName().equals(platform))
				.collect(Collectors.toCollection(ArrayList::new));

		return s;
	}

	private void reportJunitResultsToTestlink(Map<String, List<TestCase>> sortedTestcases) {
		final File file = new File(this.outputDir);
		final FileFilter fileFilter = new WildcardFileFilter(this.filename);
		final File[] files = file.listFiles(fileFilter);
		if (files != null) {
			for (final File f : files) {
				getLog().info("File - " + f.getName());
				JAXBContext jaxbContext = null;
				try {
					jaxbContext = JAXBContext.newInstance(TestSuite.class);

					final Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
					final TestSuite ts = (TestSuite) jaxbUnmarshaller.unmarshal(f);
					final com.breinjhel.model.TestCase[] tc = ts.getTestCases();

					Arrays.stream(tc).forEach(s -> {
						ExecutionStatus status;
						final String tcName = s.getClassname() + "#" + s.getName();

						if (s.getError() != null || s.getFailure() != null) {
							status = ExecutionStatus.FAILED;
						} else {
							status = ExecutionStatus.PASSED;
						}
						final Build[] builds = this.api.getBuildsForTestPlan(this.tp.getId());
						if (builds == null || Arrays.asList(builds).stream().noneMatch(o -> o.getName().equals(this.build))) {
							this.api.createBuild(this.tp.getId(), this.build, "Created by Testlink Maven Plugin");
						}

						if (sortedTestcases.containsKey(tcName)) {

							sortedTestcases.get(tcName).forEach(j -> {
								if (s.getError() != null || s.getFailure() != null) {
									final String note = s.getError() != null ? s.getError().getMessage() : s.getFailure().getMessage();
									getLog().info(tcName + " - Failed");
									this.api.reportTCResult(j.getId(), null, this.tp.getId(), status, null, this.build, note, null, null,
											null, this.platform, null, true);
								} else {
									getLog().info(tcName + " - Passed");
									this.api.reportTCResult(j.getId(), null, this.tp.getId(), status, null, this.build, "", null, null,
											null, this.platform, null, true);

								}

							});
						} else {
							getLog().info("No test case found for: " + tcName);
						}
					});
				} catch (final JAXBException e) {
					e.printStackTrace();
				}
			}
		} else {
			getLog().warn("No test results found in " + this.outputDir + "/" + this.filename);
		}
	}

}
