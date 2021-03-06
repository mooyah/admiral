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

import { Component, OnInit } from '@angular/core';
import { FormGroup, FormControl } from "@angular/forms";
import { DocumentService } from "../../utils/document.service";

@Component({
    selector: 'app-identity-usersgroups',
    templateUrl: './users-groups.component.html',
    styleUrls: ['./users-groups.component.scss']
})
/**
 * Tab displaying the users and groups in the system.
 */
export class UsersGroupsComponent implements OnInit {

    searchPrincipalsForm = new FormGroup({
        searchField: new FormControl('')
    });

    selected: any[] = [];
    selectedPrincipals: any[] = [];

    constructor(protected service: DocumentService) {
    }

    ngOnInit() {
    }

    searchIt($event) {
        let searchTerm = this.searchPrincipalsForm.get("searchField").value;
        console.log('searchTerm', searchTerm, $event);

        if (searchTerm === '') {
            this.selectedPrincipals = [];
            return;
        }

        this.service.findPrincipals(searchTerm, true).then((principalsResult) => {
            this.selectedPrincipals = principalsResult;
        }).catch((error) => {
            console.log('Failed to find principals', error);
        });
    }

    isCloudAdmin(principal: any) {
        let roles: any[] = principal.roles;
        if (!roles) {
            return false;
        }

        let cloudAdminRole:any = roles.find((role) => {
            return role === 'CLOUD_ADMINS';
        });

        return !!cloudAdminRole;
    }

    getProjectsRoles(principal: any) {
        let projectsRoles: any[] = [];

        let projects = principal.projects;
        if (projects) {
            projects.forEach((project) => {
                if (project.roles && project.roles.length > 0) {
                    projectsRoles.push({
                        projectName: project.name,
                        projectRole: project.roles[0]
                    });
                }
            });
        }

        return projectsRoles;
    }

    onAssignRoles() {
        // TODO
    }

    onMakeAdmin() {
        // TODO
    }
}
