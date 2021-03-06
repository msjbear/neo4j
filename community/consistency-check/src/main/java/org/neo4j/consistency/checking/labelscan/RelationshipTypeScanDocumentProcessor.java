/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.consistency.checking.labelscan;

import org.neo4j.consistency.checking.full.RecordProcessor;
import org.neo4j.consistency.report.ConsistencyReporter;
import org.neo4j.consistency.store.synthetic.TokenScanDocument;
import org.neo4j.internal.index.label.EntityTokenRange;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;

public class RelationshipTypeScanDocumentProcessor extends RecordProcessor.Adapter<EntityTokenRange>
{
    private final ConsistencyReporter reporter;
    private final RelationshipTypeScanCheck relationshipTypeScanCheck;

    public RelationshipTypeScanDocumentProcessor( ConsistencyReporter reporter, RelationshipTypeScanCheck relationshipTypeScanCheck )
    {
        this.reporter = reporter;
        this.relationshipTypeScanCheck = relationshipTypeScanCheck;
    }

    @Override
    public void process( EntityTokenRange entityTokenRange, PageCursorTracer cursorTracer )
    {
        reporter.forRelationshipTypeScan( new TokenScanDocument( entityTokenRange ), relationshipTypeScanCheck, cursorTracer );
    }
}
