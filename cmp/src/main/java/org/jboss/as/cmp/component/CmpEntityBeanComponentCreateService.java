/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.cmp.component;

import org.jboss.as.cmp.jdbc.JDBCEntityPersistenceStore;
import org.jboss.as.cmp.jdbc.metadata.JDBCEntityMetaData;
import org.jboss.as.ee.component.BasicComponent;
import org.jboss.as.ee.component.BasicComponentCreateService;
import org.jboss.as.ee.component.ComponentConfiguration;
import org.jboss.as.ee.component.ComponentCreateServiceFactory;
import org.jboss.as.ejb3.component.EJBComponentCreateServiceFactory;
import org.jboss.as.ejb3.component.entity.EntityBeanComponentCreateService;
import org.jboss.as.ejb3.deployment.EjbJarConfiguration;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.value.InjectedValue;

/**
 * TODO: merge with EntityBeanComponentCreateService
 *
 * @author John Bailey
 */
public class CmpEntityBeanComponentCreateService extends EntityBeanComponentCreateService {

    public static final ComponentCreateServiceFactory FACTORY = new EJBComponentCreateServiceFactory() {
        public BasicComponentCreateService constructService(final ComponentConfiguration configuration) {
            return new CmpEntityBeanComponentCreateService(configuration, this.ejbJarConfiguration);
        }
    };

    private final InjectedValue<JDBCEntityPersistenceStore> storeManager = new InjectedValue<JDBCEntityPersistenceStore>();
    private final JDBCEntityMetaData entityMetaData;

    public CmpEntityBeanComponentCreateService(final ComponentConfiguration componentConfiguration, final EjbJarConfiguration ejbJarConfiguration) {
        super(componentConfiguration, ejbJarConfiguration);
        entityMetaData = CmpEntityBeanComponentDescription.class.cast(componentConfiguration.getComponentDescription()).getEntityMetaData();
    }

    @Override
    protected BasicComponent createComponent() {
        return new CmpEntityBeanComponent(this);
    }

    public JDBCEntityMetaData getEntityMetaData() {
        return entityMetaData;
    }

    public JDBCEntityPersistenceStore getStoreManager() {
        return storeManager.getValue();
    }

    public Injector<JDBCEntityPersistenceStore> getStoreManagerInjector() {
        return storeManager;
    }
}
