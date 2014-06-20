package com.intellij.structuralsearch;

import com.intellij.structuralsearch.plugin.replace.ReplaceOptions;
import com.intellij.openapi.fileTypes.StdFileTypes;

import java.io.IOException;

/**
 * Created by IntelliJ IDEA.
 * User: maxim.mossienko
 * Date: Oct 11, 2005
 * Time: 10:10:21 PM
 * To change this template use File | Settings | File Templates.
 */
@SuppressWarnings({"ALL"})
public class XmlStructuralReplaceTest extends StructuralReplaceTestCase {
  private ReplaceOptions xmlOptions;
  
  public void setUp() throws Exception {
    super.setUp();
    xmlOptions = options.clone();
    xmlOptions.getMatchOptions().setFileType(StdFileTypes.XML);
  }
  
  public void testReplaceXmlAndHtml() {
    
    String s1 = "<a/>";
    String s2 = "<a/>";
    String s3 = "<a><b/></a>";

    String expectedResult = "<a><b/></a>";
    String actualResult = replacer.testReplace(s1,s2,s3,xmlOptions);

    assertEquals(
      "First tag replacement",
      expectedResult,
      actualResult
    );


    String s4 = "<group id=\"EditorTabPopupMenu\">\n" +
                "      <reference id=\"Compile\"/>\n" +
                "      <reference id=\"RunContextPopupGroup\"/>\n" +
                "      <reference id=\"ValidateXml\"/>\n" +
                "      <separator/>\n" +
                "      <reference id=\"VersionControlsGroup\"/>\n" +
                "      <separator/>\n" +
                "      <reference id=\"ExternalToolsGroup\"/>\n" +
                "</group>";
    String s5 = "<reference id=\"'Value\"/>";
    String s6 = "<reference ref=\"$Value$\"/>";

    actualResult = replacer.testReplace(s4,s5,s6,xmlOptions);
    expectedResult = "<group id=\"EditorTabPopupMenu\">\n" +
                     "      <reference ref=\"Compile\"/>\n" +
                     "      <reference ref=\"RunContextPopupGroup\"/>\n" +
                     "      <reference ref=\"ValidateXml\"/>\n" +
                     "      <separator/>\n" +
                     "      <reference ref=\"VersionControlsGroup\"/>\n" +
                     "      <separator/>\n" +
                     "      <reference ref=\"ExternalToolsGroup\"/>\n" +
                     "</group>";
    assertEquals(
      "Replace tag",
      expectedResult,
      actualResult
    );

    String s7 = "<h4 class=\"a\">My title<aaa>ZZZZ</aaa> My title 3</h4>\n" +
                "<h4>My title 2</h4>";
    String s8 = "<h4 class=\"a\">'Content*</h4>";
    String s9 = "<h5>$Content$</h5>";

    actualResult = replacer.testReplace(s7,s8,s9,xmlOptions);
    expectedResult = "<h5>My title <aaa>ZZZZ</aaa>  My title 3</h5>\n" +
                     "<h4>My title 2</h4>";

    assertEquals(
      "Replace tag saving content",
      expectedResult,
      actualResult
    );
  }

  public void testHtmlReplacement1() throws IOException {
    doHtmlReplacementFromTestDataFiles("in1.html", "pattern1_2.html", "replacement1_2.html", "out1.html", "Large html replacement", false);
  }

  public void testHtmlReplacement2() throws IOException {
    doHtmlReplacementFromTestDataFiles("in2.html", "pattern2.html", "replacement2.html", "out2.html", "Large html replacement 2",true);
  }

  public void testHtmlReplacement3() throws IOException {
    doHtmlReplacementFromTestDataFiles("in3.html", "pattern3.html", "replacement3.html", "out3.html", "Html replacement 3",true);
  }

  private void doHtmlReplacementFromTestDataFiles(final String inFileName,
                                                  final String patternFileName,
                                                  final String replacementFileName,
                                                  final String outFileName,
                                                  final String message, boolean filepattern) throws IOException {
    xmlOptions.getMatchOptions().setFileType(StdFileTypes.HTML);

    String content = TestUtils.loadFile(inFileName);
    String pattern = TestUtils.loadFile(patternFileName);
    String replacement = TestUtils.loadFile(replacementFileName);
    String expectedResult = TestUtils.loadFile(outFileName);

    actualResult = replacer.testReplace(content,pattern,replacement,xmlOptions,filepattern);
    assertEquals(message,
                 expectedResult,
                 actualResult
    );

    xmlOptions.getMatchOptions().setFileType(StdFileTypes.XML);
  }
}
