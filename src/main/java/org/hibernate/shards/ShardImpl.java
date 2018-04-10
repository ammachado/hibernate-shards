/**
 * Copyright (C) 2007 Google Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA
 */

package org.hibernate.shards;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Criteria;
import org.hibernate.Interceptor;
import org.hibernate.Session;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.shards.criteria.CriteriaEvent;
import org.hibernate.shards.criteria.CriteriaId;
import org.hibernate.shards.criteria.ShardedCriteria;
import org.hibernate.query.Query;
import org.hibernate.shards.query.QueryEvent;
import org.hibernate.shards.query.QueryId;
import org.hibernate.shards.query.ShardedQuery;
import org.hibernate.shards.query.ShardedSQLQuery;
import org.hibernate.shards.session.OpenSessionEvent;
import org.hibernate.shards.util.Lists;
import org.hibernate.shards.util.Preconditions;
import org.hibernate.shards.util.Sets;

/**
 * Concrete implementation of the {@link Shard} interface.
 *
 * @author maxr@google.com (Max Ross)
 */
public class ShardImpl implements Shard {

	// ids of virtual shards mapped to this physical shard
	private final Set<ShardId> shardIds;

	// the SessionFactory that owns this Session
	private final SessionFactoryImplementor sessionFactory;

	// the interceptor to be used when instantiating the Session
	private final Interceptor interceptor;

	// the Set of shardIds is immutable, so we calculate the hashcode once up
	// front and hole on to it as an optimization
	private final int hashCode;
	/**
	 * the actual Session!  Will be null until someone calls {@code establishSession()}
	 */
	private Session session;

	// events that need to fire when the Session is opened
	private final LinkedList<OpenSessionEvent> openSessionEvents = new LinkedList<>();

	// maps criteria ids to Criteria objects for quick lookup
	private Map<CriteriaId, Criteria> criteriaMap = new HashMap<>();

	// maps query ids to Query objects for quick lookup
	private Map<QueryId, Query> queryMap = new HashMap<>();

	// maps criteria ids to lists of criteria events.  The criteria events
	// need to fire when the actual Criteria is established
	private Map<CriteriaId, LinkedList<CriteriaEvent>> criteriaEventMap = new HashMap<>();

	// maps query ids to lists of query events.  The query events
	// need to fire when the actual Query is established
	private Map<QueryId, LinkedList<QueryEvent>> queryEventMap = new HashMap<>();

	/**
	 * Construct a ShardImpl
	 *
	 * @param shardId the logical shardId that is mapped to this physical shard
	 * @param sessionFactory the SessionFactory that we'll use to create the
	 * Session (if and when we create the Session)
	 */
	public ShardImpl(final ShardId shardId, final SessionFactoryImplementor sessionFactory) {
		this( Collections.singleton( shardId ), sessionFactory, null );
	}

	/**
	 * Construct a ShardImpl
	 *
	 * @param shardIds the logical shardIds that are mapped to this physical shard
	 * @param sessionFactory the SessionFactory that we'll use to create the
	 * Session (if and when we create the Session)
	 */
	public ShardImpl(final Set<ShardId> shardIds, final SessionFactoryImplementor sessionFactory) {
		this( shardIds, sessionFactory, null );
	}

	/**
	 * Construct a ShardImpl
	 *
	 * @param shardIds the logical shardIds that are mapped to this physical shard
	 * @param sessionFactory the SessionFactory that we'll use to create the
	 * Session (if and when we create the Session)
	 * @param interceptor the interceptor that we'll pass in when we create the
	 * Session (if and when we create the Session).  Can be null.
	 */
	public ShardImpl(
			final Set<ShardId> shardIds,
			final SessionFactoryImplementor sessionFactory,
			final /*@Nullable*/ Interceptor interceptor) {

		// make a copy to be safe
		this.shardIds = Collections.unmodifiableSet( Sets.newHashSet( shardIds ) );
		this.hashCode = shardIds.hashCode();
		this.sessionFactory = sessionFactory;
		this.interceptor = interceptor;
	}

	@Override
	public SessionFactoryImplementor getSessionFactoryImplementor() {
		return sessionFactory;
	}

	@Override
	public Session getSession() {
		return session;
	}

	@Override
	public void addOpenSessionEvent(final OpenSessionEvent event) {
		Preconditions.checkNotNull( event );
		openSessionEvents.addLast( event );
	}

	@Override
	public Session establishSession() {
		// if we already have a session we just return it
		if ( session == null ) {
			// make sure we use the provided interceptor when we open the session
			if ( interceptor == null ) {
				session = sessionFactory.openSession();
			}
			else {
				session = sessionFactory.withOptions().interceptor( interceptor ).openSession();
			}

			// apply any OpenSessionEvents that have been queued up.
			if ( openSessionEvents != null ) {
				for ( final OpenSessionEvent event : openSessionEvents ) {
					event.onOpenSession( session );
				}
				// clear the list so they can't get fired again
				openSessionEvents.clear();
			}
		}
		return session;
	}

	@Override
	public Criteria getCriteriaById(final CriteriaId id) {
		return criteriaMap.get( id );
	}

	@Override
	public void addCriteriaEvent(final CriteriaId id, final CriteriaEvent event) {
		addEventToMap( criteriaEventMap, id, event );
	}

	@Override
	public Criteria establishCriteria(final ShardedCriteria shardedCriteria) {
		final CriteriaId critId = shardedCriteria.getCriteriaId();
		Criteria crit = criteriaMap.get( critId );
		// if the Criteria has already been established we just return it
		if ( crit == null ) {
			// Criteria does not yet exist so need to create it
			crit = shardedCriteria.getCriteriaFactory().createCriteria( establishSession() );
			// add it to the map right away in case some of our events require it
			criteriaMap.put( critId, crit );
			// see if we have events that we need to apply to the Criteria
			final List<CriteriaEvent> events = criteriaEventMap.get( critId );
			if ( events != null ) {
				// we've got events, fire away
				for ( final CriteriaEvent event : events ) {
					event.onEvent( crit );
				}
				// clear the list so they can't get fired again
				events.clear();
			}
		}
		return crit;
	}

	@Override
	public Query getQueryById(final QueryId id) {
		return queryMap.get( id );
	}

	@Override
	public void addQueryEvent(final QueryId id, final QueryEvent event) {
		addEventToMap( queryEventMap, id, event );
	}

	@Override
	public Query establishQuery(final ShardedQuery shardedQuery) {
		final QueryId queryId = shardedQuery.getQueryId();
		Query query = queryMap.get( queryId );
		if ( query == null ) {
			// Criteria does not yet exist so need to create it
			if ( shardedQuery instanceof ShardedSQLQuery ) {
				query = shardedQuery.getQueryFactory().createSQLQuery( establishSession() );
			}
			else {
				query = shardedQuery.getQueryFactory().createQuery( establishSession() );
			}
			// add it to the map right away in case some of our events require it
			queryMap.put( queryId, query );
			// see if we have events that we need to apply to the Query
			final List<QueryEvent> events = queryEventMap.get( queryId );
			if ( events != null ) {
				// we've got events, fire away
				for ( final QueryEvent event : events ) {
					event.onEvent( query );
				}
				// clear the list so they can't get fired again
				events.clear();
			}
		}
		return query;
	}

	/**
	 * Equality is defined using the list of virtual shard ids that are mapped
	 * to this physical shard.
	 */
	@Override
	public boolean equals(Object o) {
		if ( this == o ) {
			return true;
		}
		if ( !(o instanceof ShardImpl) ) {
			return false;
		}

		final ShardImpl shard = (ShardImpl) o;

		if ( !shardIds.equals( shard.shardIds ) ) {
			return false;
		}
		return false;
	}

	@Override
	public int hashCode() {
		// use the cached value
		return hashCode;
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<Object> list(final CriteriaId criteriaId) {
		return criteriaMap.get( criteriaId ).list();
	}

	@Override
	public Object uniqueResult(final CriteriaId criteriaId) {
		return criteriaMap.get( criteriaId ).uniqueResult();
	}

	@Override
	@SuppressWarnings("unchecked")
	public <R> List<R> list(final QueryId queryId) {
		return queryMap.get( queryId ).list();
	}

	@Override
	public int executeUpdate(final QueryId queryId) {
		return queryMap.get( queryId ).executeUpdate();
	}

	@Override
	public <R> R uniqueResult(final QueryId queryId) {
		return (R) queryMap.get( queryId ).uniqueResult();
	}

	@Override
	public Set<ShardId> getShardIds() {
		return shardIds;
	}

	/**
	 * @return the OpenSessionEvents that are waiting to fire
	 */
	List<OpenSessionEvent> getOpenSessionEvents() {
		return openSessionEvents;
	}

	/**
	 * @return the map from CriteriaId to Criteria
	 */
	Map<CriteriaId, Criteria> getCriteriaMap() {
		return criteriaMap;
	}

	/**
	 * @return the CriteriaEvents that are waiting to fire
	 */
	Map<CriteriaId, LinkedList<CriteriaEvent>> getCriteriaEventMap() {
		return criteriaEventMap;
	}

	@Override
	public String toString() {
		return getSessionFactoryImplementor().getSettings().getSessionFactoryName();
	}

	/**
	 * @return the map from QueryId to Query
	 */
	Map<QueryId, Query> getQueryMap() {
		return queryMap;
	}

	/**
	 * @return the QueryEvents that are waiting to fire
	 */
	Map<QueryId, LinkedList<QueryEvent>> getQueryEventMap() {
		return queryEventMap;
	}

	/**
	 * @return the Interceptor we use in the construction of the Session (if
	 * and when we construct the Session).  Can be null.
	 */
	public /*@Nullable*/ Interceptor getInterceptor() {
		return interceptor;
	}

	/**
	 * Utility function for adding events to maps where the key is an id and the
	 * value is a linked list of events.
	 */
	private static <D, E> void addEventToMap(final Map<D, LinkedList<E>> map, final D id, final E event) {
		Preconditions.checkNotNull( id );
		Preconditions.checkNotNull( event );
		// see if we already have an event list for this query
		LinkedList<E> events = map.get( id );
		if ( events == null ) {
			// no list yet, so create it
			events = Lists.newLinkedList();
			map.put( id, events );
		}
		// always add events to the end
		events.addLast( event );
	}
}

