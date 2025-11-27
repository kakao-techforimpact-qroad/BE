package com.qroad.be.external.policy;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PolicyNewsDTO {
    @JacksonXmlProperty(localName = "NewsItemId")
    private String newsId;

    @JacksonXmlProperty(localName = "ContentsStatus")
    private String contentsStatus;

    @JacksonXmlProperty(localName = "ModifyId")
    private String modifyId;

    @JacksonXmlProperty(localName = "ModifyDate")
    private String modifyDate;

    @JacksonXmlProperty(localName = "ApproveDate")
    private String approveDate;

    @JacksonXmlProperty(localName = "ApproverName")
    private String approverName;

    @JacksonXmlProperty(localName = "EmbargoDate")
    private String embargoDate;

    @JacksonXmlProperty(localName = "GroupingCode")
    private String groupingCode;

    @JacksonXmlProperty(localName = "Title")
    private String title;

    @JacksonXmlProperty(localName = "SubTitle1")
    private String subTitle1;

    @JacksonXmlProperty(localName = "SubTitle2")
    private String subTitle2;

    @JacksonXmlProperty(localName = "SubTitle3")
    private String subTitle3;

    @JacksonXmlProperty(localName = "ContentsType")
    private String contentsType;

    @JacksonXmlProperty(localName = "MinisterCode")
    private String ministerName;

    @JacksonXmlProperty(localName = "OriginalUrl")
    private String originalUrl;

    @JacksonXmlProperty(localName = "ThumbnailUrl")
    private String thumbnailUrl;

    @JacksonXmlProperty(localName = "OriginalimgUrl")
    private String originalImgUrl;

    @JacksonXmlProperty(localName = "DataContents")
    private String dataContents;
}
