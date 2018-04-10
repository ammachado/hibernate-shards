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

package org.hibernate.shards.session;

import org.hibernate.Session;

/**
 * OpenSessionEvent which sets the provided Session on a RequiresSession.
 *
 * @author maxr@google.com (Max Ross)
 */
public class SetSessionOnRequiresSessionEvent implements OpenSessionEvent {

	private final RequiresSession requiresSession;

	SetSessionOnRequiresSessionEvent(RequiresSession requiresSession) {
		this.requiresSession = requiresSession;
	}

	@Override
	public void onOpenSession(Session session) {
		requiresSession.setSession( session );
	}
}
