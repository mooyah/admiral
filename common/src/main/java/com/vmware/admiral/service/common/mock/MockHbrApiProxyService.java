/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.service.common.mock;

import static com.vmware.admiral.service.common.HbrApiProxyService.HARBOR_ENDPOINT_REPOSITORIES;
import static com.vmware.admiral.service.common.HbrApiProxyService.HARBOR_QUERY_PARAM_DETAIL;
import static com.vmware.admiral.service.common.HbrApiProxyService.HARBOR_QUERY_PARAM_PROJECT_ID;
import static com.vmware.admiral.service.common.HbrApiProxyService.HARBOR_RESP_PROP_ID;
import static com.vmware.admiral.service.common.HbrApiProxyService.HARBOR_RESP_PROP_NAME;
import static com.vmware.admiral.service.common.HbrApiProxyService.HARBOR_RESP_PROP_TAGS_COUNT;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.UriUtils;

/**
 * Simple reverse proxy service to forward requests to harbor services.
 */
public class MockHbrApiProxyService extends StatelessService {

    public static final String SELF_LINK = ManagementUriParts.HBR_REVERSE_PROXY;

    public static final int MOCKED_PROJECT_ID = 1;
    public static final Map<String, Map<String, Object>> mockedRepositories;

    private static final String REPOSITORY_1_NAME = "library/alpine";
    private static final String REPOSITORY_2_NAME = "library/alpine-again";
    private static final String REPOSITORY_1_ID = "1";
    private static final String REPOSITORY_2_ID = "2";
    private static final long REPOSITORY_1_TAGS_COUNT = 3;
    private static final long REPOSITORY_2_TAGS_COUNT = 1;

    static {
        HashMap<String, Map<String, Object>> repositories = new HashMap<>();

        HashMap<String, Object> repository1 = new HashMap<>();
        repository1.put(HARBOR_RESP_PROP_ID, REPOSITORY_1_ID);
        repository1.put(HARBOR_RESP_PROP_NAME, REPOSITORY_1_NAME);
        repository1.put(HARBOR_RESP_PROP_TAGS_COUNT, REPOSITORY_1_TAGS_COUNT);
        repositories.put(REPOSITORY_1_NAME, repository1);

        HashMap<String, Object> repository2 = new HashMap<>();
        repository2.put(HARBOR_RESP_PROP_ID, REPOSITORY_2_ID);
        repository2.put(HARBOR_RESP_PROP_NAME, REPOSITORY_2_NAME);
        repository2.put(HARBOR_RESP_PROP_TAGS_COUNT, REPOSITORY_2_TAGS_COUNT);
        repositories.put(REPOSITORY_2_NAME, repository2);

        mockedRepositories = Collections.unmodifiableMap(repositories);
    }

    private static final String HBR_API_BASE_ENDPOINT = "api";

    public MockHbrApiProxyService() {
        super();
        super.toggleOption(ServiceOption.URI_NAMESPACE_OWNER, true);
    }

    @Override
    public void handleRequest(Operation op) {

        if (op.getAction() != Action.GET) {
            logWarning("Unsupported method: %s", op.getAction().toString());
            Operation.failActionNotSupported(op);
            return;
        }

        URI requestUri = op.getUri();
        if (isRepositoriesRequest(requestUri)) {
            handleRepositoryGet(op);
        } else {
            logWarning("Unsupported URI path: %s", getHarborPath(requestUri.getPath()));
            op.fail(new IllegalStateException(
                    String.format("Path %s is not supported by the mock service.",
                            getHarborPath(requestUri.getPath()))));
        }
    }

    private void handleRepositoryGet(Operation get) {
        logInfo("Received repositories request.");
        Map<String, String> queryParams = UriUtils.parseUriQueryParams(get.getUri());

        if (!queryParams.containsKey(HARBOR_QUERY_PARAM_PROJECT_ID)) {
            logWarning("project_id was not set");
            String error = "invalid project_id";
            get.fail(Operation.STATUS_CODE_BAD_REQUEST, new IllegalArgumentException(error), error);
            return;
        }

        String projectId = queryParams.get(HARBOR_QUERY_PARAM_PROJECT_ID);
        if (!projectId.equals("" + MOCKED_PROJECT_ID)) {
            logWarning("Unknown project_id: %s", projectId);
            String error = String.format("project %s not found", projectId);
            get.fail(Operation.STATUS_CODE_NOT_FOUND, new IllegalArgumentException(error), error);
            return;
        }

        if (queryParams.containsKey(HARBOR_QUERY_PARAM_DETAIL)) {
            handleDetailedRepositoryGet(get);
        } else {
            handleSimpleRepositoryGet(get);
        }
    }

    private void handleDetailedRepositoryGet(Operation get) {
        logInfo("Handling detailed repository GET");
        get.setBody(new ArrayList<>(mockedRepositories.values()));
        get.complete();
    }

    private void handleSimpleRepositoryGet(Operation get) {
        logInfo("Handling simple repositories GET");
        get.setBody(new ArrayList<>(mockedRepositories.keySet()));
        get.complete();
    }

    private boolean isRepositoriesRequest(URI requestUri) {
        if (requestUri == null) {
            return false;
        }

        String path = getHarborPath(requestUri.getPath());
        if (path == null || path.isEmpty()) {
            return false;
        }

        return path.equalsIgnoreCase(HARBOR_ENDPOINT_REPOSITORIES)
                || path.equalsIgnoreCase(HBR_API_BASE_ENDPOINT + HARBOR_ENDPOINT_REPOSITORIES);
    }

    private String getHarborPath(String path) {
        if (path == null) {
            return path;
        }

        return path.substring(SELF_LINK.length());
    }
}
