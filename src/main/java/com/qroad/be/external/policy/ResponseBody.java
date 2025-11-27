package com.qroad.be.external.policy;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ResponseBody {

    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "NewsItem")
    private List<PolicyNewsDTO> items;

}
