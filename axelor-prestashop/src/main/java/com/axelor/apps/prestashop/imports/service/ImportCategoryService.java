/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2022 Axelor (<http://axelor.com>).
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
package com.axelor.apps.prestashop.imports.service;

import com.axelor.apps.prestashop.service.library.PrestaShopWebserviceException;
import com.axelor.studio.db.AppPrestashop;
import java.io.IOException;
import java.io.Writer;
import java.time.ZonedDateTime;

public interface ImportCategoryService {

  /**
   * Import Product categories from prestashop.
   *
   * @param bwImport object of import logfile
   * @return object import log file
   * @throws IOException
   * @throws PrestaShopWebserviceException
   */
  public void importCategory(AppPrestashop appConfig, ZonedDateTime endDate, Writer logBuffer)
      throws IOException, PrestaShopWebserviceException;
}
