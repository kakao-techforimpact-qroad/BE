package com.qroad.be.external.policy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponseWrapper {

    @JacksonXmlProperty(localName = "body")
    private ResponseBody body;

}
