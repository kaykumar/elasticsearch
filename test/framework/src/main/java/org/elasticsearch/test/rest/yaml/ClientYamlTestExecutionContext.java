/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.test.rest.yaml;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.xcontent.XContentFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context passed across the REST tests.
 * Holds the REST client used to communicate with elasticsearch.
 * Caches the last obtained test response and allows to stash part of it within variables
 * that can be used as input values in following requests.
 */
public class ClientYamlTestExecutionContext {

    private static final Logger logger = Loggers.getLogger(ClientYamlTestExecutionContext.class);

    private final Stash stash = new Stash();
    private final ClientYamlTestClient clientYamlTestClient;

    private ClientYamlTestResponse response;

    public ClientYamlTestExecutionContext(ClientYamlTestClient clientYamlTestClient) {
        this.clientYamlTestClient = clientYamlTestClient;
    }

    /**
     * Calls an elasticsearch api with the parameters and request body provided as arguments.
     * Saves the obtained response in the execution context.
     */
    public ClientYamlTestResponse callApi(String apiName, Map<String, String> params, List<Map<String, Object>> bodies,
                                    Map<String, String> headers) throws IOException {
        //makes a copy of the parameters before modifying them for this specific request
        HashMap<String, String> requestParams = new HashMap<>(params);
        // By default ask for error traces, this my be overridden by params
        if (false == (esVersion().before(Version.V_5_2_0_UNRELEASED) && apiName.endsWith("put_settings"))) {
            // But not for APIs for which it is broken....
            // You should able to remove this branch and keep the statement below after releasing 5.2
            requestParams.putIfAbsent("error_trace", "true");
        }
        for (Map.Entry<String, String> entry : requestParams.entrySet()) {
            if (stash.containsStashedValue(entry.getValue())) {
                entry.setValue(stash.getValue(entry.getValue()).toString());
            }
        }

        HttpEntity entity = createEntity(bodies);
        try {
            response = callApiInternal(apiName, requestParams, entity, headers);
            return response;
        } catch(ClientYamlTestResponseException e) {
            response = e.getRestTestResponse();
            throw e;
        } finally {
            // if we hit a bad exception the response is null
            Object responseBody = response != null ? response.getBody() : null;
            //we always stash the last response body
            stash.stashValue("body", responseBody);
        }
    }

    private HttpEntity createEntity(List<Map<String, Object>> bodies) throws IOException {
        if (bodies.isEmpty()) {
            return null;
        }
        if (bodies.size() == 1) {
            String bodyAsString = bodyAsString(stash.replaceStashedValues(bodies.get(0)));
            return new StringEntity(bodyAsString, ContentType.APPLICATION_JSON);
        }
        StringBuilder bodyBuilder = new StringBuilder();
        for (Map<String, Object> body : bodies) {
            bodyBuilder.append(bodyAsString(stash.replaceStashedValues(body))).append("\n");
        }
        return new StringEntity(bodyBuilder.toString(), ContentType.APPLICATION_JSON);
    }

    private String bodyAsString(Map<String, Object> body) throws IOException {
        return XContentFactory.jsonBuilder().map(body).string();
    }

    private ClientYamlTestResponse callApiInternal(String apiName, Map<String, String> params,
                                                   HttpEntity entity, Map<String, String> headers) throws IOException  {
        return clientYamlTestClient.callApi(apiName, params, entity, headers);
    }

    /**
     * Extracts a specific value from the last saved response
     */
    public Object response(String path) throws IOException {
        return response.evaluate(path, stash);
    }

    /**
     * Clears the last obtained response and the stashed fields
     */
    public void clear() {
        logger.debug("resetting client, response and stash");
        response = null;
        stash.clear();
    }

    public Stash stash() {
        return stash;
    }

    /**
     * Returns the current es version as a string
     */
    public Version esVersion() {
        return clientYamlTestClient.getEsVersion();
    }

}
