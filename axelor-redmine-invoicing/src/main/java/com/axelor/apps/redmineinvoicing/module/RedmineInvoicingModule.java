/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.redmineinvoicing.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.redmineinvoicing.imports.RedmineInvoicingImportService;
import com.axelor.apps.redmineinvoicing.imports.RedmineInvoicingImportServiceImpl;
import com.axelor.apps.redmineinvoicing.imports.service.ImportActivityService;
import com.axelor.apps.redmineinvoicing.imports.service.ImportActivityServiceImpl;
import com.axelor.apps.redmineinvoicing.imports.service.ImportGroupService;
import com.axelor.apps.redmineinvoicing.imports.service.ImportGroupServiceImpl;
import com.axelor.apps.redmineinvoicing.imports.service.ImportIssueService;
import com.axelor.apps.redmineinvoicing.imports.service.ImportIssueServiceImpl;
import com.axelor.apps.redmineinvoicing.imports.service.ImportProjectService;
import com.axelor.apps.redmineinvoicing.imports.service.ImportProjectServiceImpl;
import com.axelor.apps.redmineinvoicing.imports.service.ImportUserService;
import com.axelor.apps.redmineinvoicing.imports.service.ImportUserServiceImpl;
import com.axelor.apps.redmineinvoicing.service.RedmineInvoicingService;
import com.axelor.apps.redmineinvoicing.service.RedmineInvoicingServiceImpl;

public class RedmineInvoicingModule extends AxelorModule {

  @Override
  protected void configure() {
    bind(RedmineInvoicingService.class).to(RedmineInvoicingServiceImpl.class);
    bind(RedmineInvoicingImportService.class).to(RedmineInvoicingImportServiceImpl.class);
    bind(ImportGroupService.class).to(ImportGroupServiceImpl.class);
    bind(ImportUserService.class).to(ImportUserServiceImpl.class);
    bind(ImportProjectService.class).to(ImportProjectServiceImpl.class);
    bind(ImportIssueService.class).to(ImportIssueServiceImpl.class);
    bind(ImportActivityService.class).to(ImportActivityServiceImpl.class);
  }
}
