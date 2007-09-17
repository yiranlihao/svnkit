/*
 * ====================================================================
 * Copyright (c) 2004-2007 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.server.dav;

import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.tmatesoft.svn.core.ISVNDirEntryHandler;
import org.tmatesoft.svn.core.SVNDirEntry;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNLock;
import org.tmatesoft.svn.core.SVNNodeKind;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNRevisionProperty;
import org.tmatesoft.svn.core.internal.util.SVNEncodingUtil;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.util.SVNTimeUtil;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.io.SVNRepository;

/**
 * @author TMate Software Ltd.
 * @version 1.1.2
 */
public class DAVResource {

    public static final long INVALID_REVISION = SVNRepository.INVALID_REVISION;

    public static final String DEFAULT_COLLECTION_CONTENT_TYPE = "text/html; charset=\"utf-8\"";
    public static final String DEFAULT_FILE_CONTENT_TYPE = "text/plain";

    private DAVResourceURI myResourceURI;

    private SVNRepository myRepository;
    private long myRevision;
    private long myLatestRevision = INVALID_REVISION;

    private boolean myIsExists = false;
    private boolean myIsCollection;
    private boolean myIsSVNClient;
    private String myDeltaBase;
    private long myVersion;
    private String myClientOptions;
    private String myBaseChecksum;
    private String myResultChecksum;

    private Map mySVNProperties;
    private Collection myDeadProperties;
    private Collection myEntries;

    private boolean myChecked = false;

    /**
     * DAVResource  constructor
     *
     * @param repository   repository resource connect to
     * @param context      contains requested url requestContext and name of repository if servlet use SVNParentPath directive.
     * @param uri          special uri for DAV requests can be /path or /SPECIAL_URI/xxx/path
     * @param label        request's label header
     * @param useCheckedIn special case for VCC resource
     * @throws SVNException if an error occurs while fetching repository properties.
     */
    public DAVResource(SVNRepository repository, String context, String uri, String label, boolean useCheckedIn) throws SVNException {
        myRepository = repository;
        myResourceURI = new DAVResourceURI(context, uri, label, useCheckedIn);
        myRevision = myResourceURI.getRevision();
        myIsExists = myResourceURI.exists();
        prepare();
    }

    public DAVResource(SVNRepository repository, DAVResourceURI resourceURI, boolean isSVNClient, String deltaBase, long version, String clientOptions,
                       String baseChecksum, String resultChecksum) throws SVNException {
        myRepository = repository;
        myResourceURI = resourceURI;
        myIsSVNClient = isSVNClient;
        myDeltaBase = deltaBase;
        myVersion = version;
        myClientOptions = clientOptions;
        myBaseChecksum = baseChecksum;
        myResultChecksum = resultChecksum;
        myRevision = resourceURI.getRevision();
        myIsExists = resourceURI.exists();
        prepare();
    }

    public static boolean isValidRevision(long revision) {
        return revision >= 0;
    }

    public DAVResourceURI getResourceURI() {
        return myResourceURI;
    }

    public void setResourceURI(DAVResourceURI resourceURI) {
        myResourceURI = resourceURI;
    }

    public SVNRepository getRepository() {
        return myRepository;
    }

    public long getRevision() throws SVNException {
        if (getResourceURI().getType() == DAVResourceType.REGULAR || getResourceURI().getType() == DAVResourceType.VERSION) {
            if (!isValidRevision(myRevision)) {
                myRevision = getLatestRevision();
            }
        }
        return myRevision;
    }

    public boolean exists() throws SVNException {
        if (getResourceURI().getType() == DAVResourceType.REGULAR ||
                (getResourceURI().getType() == DAVResourceType.WORKING && !getResourceURI().isBaseLined())) {
            checkPath();
        } else if (getResourceURI().getType() == DAVResourceType.VERSION ||
                (getResourceURI().getType() == DAVResourceType.WORKING && getResourceURI().isBaseLined())) {
            myIsExists = true;
        }
        return myIsExists;
    }

    private void setExists(boolean isExist) {
        myIsExists = isExist;
    }

    public boolean isCollection() throws SVNException {
        if (getResourceURI().getType() == DAVResourceType.REGULAR || (getResourceURI().getType() == DAVResourceType.WORKING && !getResourceURI().isBaseLined())) {
            checkPath();
        }
        return myIsCollection;
    }

    private void setCollection(boolean isCollection) {
        myIsCollection = isCollection;
    }

    public boolean isSVNClient() {
        return myIsSVNClient;
    }

    public String getDeltaBase() {
        return myDeltaBase;
    }

    public long getVersion() {
        return myVersion;
    }

    public String getClientOptions() {
        return myClientOptions;
    }

    public String getBaseChecksum() {
        return myBaseChecksum;
    }


    public String getResultChecksum() {
        return myResultChecksum;
    }

    private Map getSVNProperties() throws SVNException {
        if (mySVNProperties == null) {
            mySVNProperties = new HashMap();
            if (getResourceURI().getType() == DAVResourceType.REGULAR) {
                if (isCollection()) {
                    getRepository().getDir(getResourceURI().getPath(), getRevision(), mySVNProperties, (ISVNDirEntryHandler) null);
                } else {
                    getRepository().getFile(getResourceURI().getPath(), getRevision(), mySVNProperties, null);
                }
            }
        }
        return mySVNProperties;
    }


    private boolean isChecked() {
        return myChecked;
    }

    private void setChecked(boolean checked) {
        myChecked = checked;
    }

    private void prepare() throws SVNException {
        if (getResourceURI().getType() == DAVResourceType.VERSION) {
            getResourceURI().setURI(DAVPathUtil.buildURI(null, DAVResourceKind.BASELINE, getRevision(), null));
        } else if (getResourceURI().getType() == DAVResourceType.WORKING) {
            //TODO: Define filename for ACTIVITY_ID under the repository
        } else if (getResourceURI().getType() == DAVResourceType.ACTIVITY) {
            //TODO: Define filename for ACTIVITY_ID under the repository
        }
    }

    private void checkPath() throws SVNException {
        if (!isChecked()) {
            SVNNodeKind currentNodeKind = getRepository().checkPath(getResourceURI().getPath(), getRevision());
            setExists(currentNodeKind != SVNNodeKind.NONE && currentNodeKind != SVNNodeKind.UNKNOWN);
            setCollection(currentNodeKind == SVNNodeKind.DIR);
            setChecked(true);
        }
    }

    public Collection getEntries() throws SVNException {
        if (isCollection() && myEntries == null) {
            myEntries = new ArrayList();
            getRepository().getDir(getResourceURI().getPath(), getRevision(), null, SVNDirEntry.DIRENT_KIND, myEntries);
        }
        return myEntries;
    }

    private boolean lacksETagPotential() throws SVNException {
        return (!exists() || (getResourceURI().getType() != DAVResourceType.REGULAR && getResourceURI().getType() != DAVResourceType.VERSION)
                || getResourceURI().getType() == DAVResourceType.VERSION && getResourceURI().isBaseLined());
    }

    public long getCreatedRevision() throws SVNException {
        String revisionParameter = getProperty(SVNProperty.COMMITTED_REVISION);
        try {
            return Long.parseLong(revisionParameter);
        } catch (NumberFormatException e) {
            return getRevision();
        }
    }

    public long getCreatedRevision(String path, long revision) throws SVNException {
        if (path == null) {
            return INVALID_REVISION;
        } else if (path.equals(getResourceURI().getPath())) {
            return getCreatedRevision();
        } else {
            SVNDirEntry currentEntry = getRepository().getDir(path, revision, false, null);
            return currentEntry.getRevision();
        }

    }

    public Date getLastModified() throws SVNException {
        if (lacksETagPotential()) {
            return null;
        }
        return getRevisionDate(getCreatedRevision());
    }


    public Date getRevisionDate(long revision) throws SVNException {
        return SVNTimeUtil.parseDate(getRevisionProperty(revision, SVNRevisionProperty.DATE));
    }

    public String getETag() throws SVNException {
        if (lacksETagPotential()) {
            return null;
        }
        StringBuffer eTag = new StringBuffer();
        eTag.append(isCollection() ? "W/" : "");
        eTag.append("\"");
        eTag.append(getCreatedRevision());
        eTag.append("/");
        eTag.append(SVNEncodingUtil.uriEncode(getResourceURI().getPath()));
        eTag.append("\"");
        return eTag.toString();
    }

    public String getRepositoryUUID(boolean forceConnect) throws SVNException {
        return getRepository().getRepositoryUUID(forceConnect);
    }

    public String getContentType() throws SVNException {
        if (getResourceURI().isBaseLined() && getResourceURI().getType() == DAVResourceType.VERSION) {
            SVNErrorManager.error(SVNErrorMessage.create(SVNErrorCode.RA_DAV_PROPS_NOT_FOUND, "Failed to determine property"));
            return null;
        }
        if (getResourceURI().getType() == DAVResourceType.PRIVATE && getResourceURI().getKind() == DAVResourceKind.VCC) {
            SVNErrorManager.error(SVNErrorMessage.create(SVNErrorCode.RA_DAV_PROPS_NOT_FOUND, "Failed to determine property"));
            return null;
        }
        if (isCollection()) {
            return DEFAULT_COLLECTION_CONTENT_TYPE;
        }
        String contentType = getProperty(SVNProperty.MIME_TYPE);
        if (contentType != null) {
            return contentType;
        }
        return DEFAULT_FILE_CONTENT_TYPE;
    }

    public long getLatestRevision() throws SVNException {
        if (!isValidRevision(myLatestRevision)) {
            myLatestRevision = getRepository().getLatestRevision();
        }
        return myLatestRevision;
    }

    public long getContentLength() throws SVNException {
        SVNDirEntry entry = getRepository().getDir(getResourceURI().getPath(), getRevision(), false, null);
        return entry.getSize();
    }

    public SVNLock[] getLocks() throws SVNException {
        if (getResourceURI().getPath() == null) {
            SVNErrorManager.error(SVNErrorMessage.create(SVNErrorCode.RA_DAV_REQUEST_FAILED, "get-locks-report run on resource which doesn't represent a path within a repository."));
        }
        return getRepository().getLocks(getResourceURI().getPath());
    }

    public String getAuthor(long revision) throws SVNException {
        return getRevisionProperty(revision, SVNRevisionProperty.AUTHOR);
    }

    public String getMD5Checksum() throws SVNException {
        return getProperty(SVNProperty.CHECKSUM);
    }

    public Collection getDeadProperties() throws SVNException {
        if (myDeadProperties == null) {
            myDeadProperties = new ArrayList();
            for (Iterator iterator = getSVNProperties().keySet().iterator(); iterator.hasNext();) {
                String propertyName = (String) iterator.next();
                if (SVNProperty.isRegularProperty(propertyName)) {
                    myDeadProperties.add(propertyName);
                }
            }
        }
        return myDeadProperties;
    }

    public String getLog(long revision) throws SVNException {
        return getRevisionProperty(revision, SVNRevisionProperty.LOG);
    }

    public String getProperty(String propertyName) throws SVNException {
        return (String) getSVNProperties().get(propertyName);
    }

    public String getRevisionProperty(long revision, String propertyName) throws SVNException {
        return getRepository().getRevisionPropertyValue(revision, propertyName);
    }

    public void writeTo(OutputStream out) throws SVNException {
        if (isCollection()) {
            SVNErrorManager.error(SVNErrorMessage.create(SVNErrorCode.RA_DAV_REQUEST_FAILED));
        }
        getRepository().getFile(getResourceURI().getPath(), getRevision(), null, out);
    }

    public File getFile() {
        String absolutePath = SVNPathUtil.append(getRepository().getLocation().getPath(), getResourceURI().getPath());
        return new File(absolutePath);
    }
}
