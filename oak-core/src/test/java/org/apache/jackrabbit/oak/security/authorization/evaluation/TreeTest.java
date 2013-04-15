/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.security.authorization.evaluation;

import java.util.List;

import com.google.common.collect.ImmutableList;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.security.authorization.AccessControlConstants;
import org.apache.jackrabbit.oak.security.privilege.PrivilegeConstants;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TreeTest extends AbstractOakCoreTest {

    // TODO: add tests for acls withs restrictions
    // TODO: add tests with READ_PROPERTIES and READ_NODES privileges

    private Root testRoot;

    @Before
    public void before() throws Exception {
        super.before();

        setupPermission("/", testPrincipal, true, PrivilegeConstants.JCR_READ);
        setupPermission("/a/bb", testPrincipal, false, PrivilegeConstants.JCR_READ);

        testRoot = getTestRoot();
    }

    @Test
    public void testHasChild() throws Exception {
        Tree rootTree = testRoot.getTree("/");

        assertTrue(rootTree.hasChild("a"));
        assertFalse(rootTree.hasChild(AccessControlConstants.REP_POLICY));

        Tree a = rootTree.getChild("a");
        assertTrue(a.hasChild("b"));
        assertFalse(a.hasChild("bb"));

        Tree b = a.getChild("b");
        assertTrue(b.hasChild("c"));
    }

    @Test
    public void testGetChild() throws Exception {
        Tree rootTree = testRoot.getTree("/");
        assertNotNull(rootTree);

        Tree a = rootTree.getChild("a");
        assertNotNull(a);

        Tree b = a.getChild("b");
        assertNotNull(b);
        assertNotNull(b.getChild("c"));

        assertNull(a.getChild("bb"));
    }

    @Test
    public void testPolicyChild() throws Exception {
        assertNotNull(root.getTree('/' + AccessControlConstants.REP_POLICY));

        // 'testUser' must not have access to the policy node
        Tree rootTree = testRoot.getTree("/");

        assertFalse(rootTree.hasChild(AccessControlConstants.REP_POLICY));
        assertNull(rootTree.getChild(AccessControlConstants.REP_POLICY));
    }

    @Test
	public void testGetChildrenCount() throws Exception {
        long cntRoot = root.getTree("/").getChildrenCount();
        long cntA = root.getTree("/a").getChildrenCount();

        // 'testUser' may only see 'regular' child nodes -> count must be adjusted.
        assertEquals(cntRoot-1, testRoot.getTree("/").getChildrenCount());
        assertEquals(cntA - 1, testRoot.getTree("/a").getChildrenCount());

        // for the following nodes the cnt must not differ
        List<String> paths = ImmutableList.of("/a/b", "/a/b/c");
        for (String path : paths) {
            assertEquals(
                    root.getTree(path).getChildrenCount(),
                    testRoot.getTree(path).getChildrenCount());
        }
    }

    @Test
    public void testHasProperty() throws Exception {
        // TODO
    }

    @Test
    public void testGetProperty() throws Exception {
        // TODO
    }

    @Test
    public void testGetPropertyStatus() throws Exception {
        // TODO
    }

    @Test
    public void testGetPropertyCount() throws Exception {
        // TODO
    }

    @Test
    public void testGetProperties() throws Exception {
        // TODO
    }
}