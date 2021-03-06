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

package com.vmware.admiral.compute.kubernetes.service;

import static com.vmware.admiral.common.util.ServiceUtils.addServiceRequestRoute;
import static com.vmware.admiral.common.util.YamlMapper.splitYaml;
import static com.vmware.admiral.compute.content.CompositeTemplateUtil.isNullOrEmpty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.compute.kubernetes.service.KubernetesDescriptionService.KubernetesDescription;
import com.vmware.xenon.common.LocalizableValidationException;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.StatelessService;
import com.vmware.xenon.common.Utils;

/**
 * Service for parsing single YAML file which contains multiple
 * YAML kubernetes definitions and creating multiple Kubernetes Descriptions.
 */
public class KubernetesDescriptionContentService extends StatelessService {
    public static final String SELF_LINK = ManagementUriParts.KUBERNETES_DESC_CONTENT;

    private static final String FAIL_ON_CREATE_MSG = "Failed to create Kubernetes Descriptions.";

    @Override
    public void handlePost(Operation post) {
        if (!post.hasBody()) {
            post.fail(new LocalizableValidationException("body is required", "compute.body"
                    + ".required"));
            return;
        }

        List<String> kubernetesDefinitions = splitYaml(post.getBody(String.class));
        OperationJoin.create(createOperations(kubernetesDefinitions))
                .setCompletion((ops, errors) -> {
                    List<String> resourceLinks = new ArrayList<>();
                    ops.values().forEach(o -> {
                        if (o == null) {
                            return;
                        }
                        KubernetesDescription desc = o.getBody(KubernetesDescription.class);
                        if (!isNullOrEmpty(desc.documentSelfLink)) {
                            resourceLinks.add(desc.documentSelfLink);
                        }
                    });
                    if (errors != null) {
                        errors.values().forEach(e -> logWarning("Failed to create "
                                + "KubernetesDescription: %s", Utils.toString(e)));
                        cleanKubernetesDescriptionsAndFail(resourceLinks, errors, post);
                    } else {
                        post.setBody(resourceLinks);
                        post.complete();
                    }
                }).sendWith(this);
    }

    private List<Operation> createOperations(List<String> kubernetesDefinitions) {
        List<Operation> ops = kubernetesDefinitions.stream()
                .map(yaml -> {
                    KubernetesDescription description = new KubernetesDescription();
                    description.kubernetesEntity = yaml;
                    return Operation.createPost(this, KubernetesDescriptionService.FACTORY_LINK)
                            .setBody(description);
                }).collect(Collectors.toList());
        return ops;
    }

    private void cleanKubernetesDescriptionsAndFail(List<String> selfLinks, Map<Long, Throwable>
            errors, Operation op) {
        if (selfLinks == null || selfLinks.isEmpty()) {
            failOperation(op, errors, new IllegalStateException(FAIL_ON_CREATE_MSG));
            return;
        }
        logWarning("Cleaning successfully created Kubernetes Descriptions");
        List<Operation> deleteOps = new ArrayList<>();
        for (String selfLink : selfLinks) {
            deleteOps.add(Operation.createDelete(this, selfLink));
        }
        OperationJoin.create(deleteOps)
                .setCompletion((ops, errs) -> {
                    if (errs != null) {
                        errs.values().forEach(e -> logWarning(Utils.toString(e)));
                    }
                    op.fail(new IllegalStateException(FAIL_ON_CREATE_MSG));
                }).sendWith(this);
    }

    private void failOperation(Operation op, Map<Long, Throwable> errors, Throwable defaultError) {
        if (isNullOrEmpty(errors)) {
            op.fail(defaultError);
        } else {
            List<Throwable> throwables = errors.values().stream()
                    .filter(e -> e != null)
                    .collect(Collectors.toList());
            op.fail(throwables.get(0));
        }
    }

    @Override
    public ServiceDocument getDocumentTemplate() {
        ServiceDocument d = super.getDocumentTemplate();
        addServiceRequestRoute(d, Action.POST,
                "Post single or multiple kubernetes YAML definitions to create composite "
                        + "description. If there are multiple YAML definitions separate them with"
                        + " \"---\".", String[].class);
        return d;
    }
}
