/**
 * Copyright (C) 2007 Google Inc.
 * <p>
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * <p>
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA
 */

package org.hibernate.shards.integration.model;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.internal.StatefulPersistenceContext;
import org.hibernate.internal.SessionImpl;
import org.hibernate.shards.integration.IdGenType;
import org.hibernate.shards.integration.platform.DatabasePlatform;
import org.hibernate.shards.integration.platform.DatabasePlatformFactory;
import org.hibernate.shards.model.Person;
import org.hibernate.shards.model.Tenant;
import org.hibernate.shards.util.JdbcUtil;
import org.hibernate.shards.util.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.hibernate.shards.integration.model.ModelDataFactory.person;
import static org.hibernate.shards.integration.model.ModelDataFactory.tenant;
import static org.junit.Assert.assertEquals;

/**
 * @author maxr@google.com (Max Ross)
 */
public class MemoryLeakTest {

	private SessionFactory sf;
	private Session session;

	private Connection getConnection(final DatabasePlatform dbPlatform) throws SQLException {
		return DriverManager.getConnection(
				dbPlatform.getUrl( 0 ),
				dbPlatform.getUser(),
				dbPlatform.getPassword()
		);
	}

	private void deleteDatabase(final DatabasePlatform dbPlatform) throws SQLException {
		try (Connection conn = getConnection( dbPlatform )) {
			for ( final String statement : dbPlatform.getDropTableStatements( IdGenType.SIMPLE ) ) {
				try {
					JdbcUtil.executeUpdate( conn, statement, false );
				}
				catch (SQLException sqle) {
					// not interested, keep moving
				}
			}
		}
	}

	private void createDatabase(final DatabasePlatform dbPlatform) throws SQLException {
		try (Connection conn = getConnection( dbPlatform )) {
			for ( final String statement : dbPlatform.getCreateTableStatements( IdGenType.SIMPLE ) ) {
				JdbcUtil.executeUpdate( conn, statement, false );
			}
		}
	}

	@Before
	public void setUp() throws Exception {
		final DatabasePlatform dbPlatform = DatabasePlatformFactory.FACTORY.getDatabasePlatform();
		deleteDatabase( dbPlatform );
		createDatabase( dbPlatform );

		final String dbPlatformConfigDirectory = "../platform/" + dbPlatform.getName().toLowerCase() + "/" + "config/";
		final Configuration configuration = new Configuration();
		configuration.configure( getClass().getResource( dbPlatformConfigDirectory + "shard0.hibernate.cfg.xml" ) );
		configuration.addURL( getClass().getResource( dbPlatformConfigDirectory + IdGenType.SIMPLE.getMappingFile() ) );

		sf = configuration.buildSessionFactory();
		session = sf.openSession();
	}

	@After
	public void tearDown() {
		try {
			session.close();
		}
		finally {
			sf.close();
		}
	}

	@Test
	@Ignore("Discover a way to do memory leak test with hibernate.")
	public void testLeak() throws NoSuchFieldException, IllegalAccessException {
		Person p;
		try {
			session.beginTransaction();
			p = person( "max", null );
			final Tenant t = tenant( "tenant", null, Lists.newArrayList( p ) );
			session.save( t );
			session.getTransaction().commit();
		}
		finally {
			session.close();
		}
		session = sf.openSession();
		session.get( Person.class, p.getPersonId() );
		final Field f = StatefulPersistenceContext.class.getDeclaredField( "proxiesByKey" );
		boolean isAccessible = f.isAccessible();
		f.setAccessible( true );
		try {
			final SessionImpl sessionImpl = (SessionImpl) session;
			final Map map = (Map) f.get( sessionImpl.getPersistenceContext() );
			assertEquals( 1, map.size() );
		}
		finally {
			f.setAccessible( isAccessible );
		}
	}
}
