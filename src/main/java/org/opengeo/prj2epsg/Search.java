/* Copyright (c) 2010 Openplans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 3.0 license, available at the root
 * application directory.
 */
package org.opengeo.prj2epsg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Version;
import org.geotools.referencing.CRS;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.IdentifiedObject;
import org.opengis.referencing.crs.CompoundCRS;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.crs.ProjectedCRS;
import org.opengis.referencing.operation.Projection;
import org.restlet.Context;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Method;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.resource.Representation;
import org.restlet.resource.ResourceException;
import org.restlet.resource.Variant;

/**
 * The search/results resource
 * @author aaime
 */
public class Search extends BaseResource {

    static final int PRJ_MAX_SIZE_KB = 4;
    
    static Directory LUCENE_INDEX;
    static IndexSearcher SEARCHER;

    public enum SearchMode {
        wkt, keywords, auto
    };

    public Search(Context context, Request request, Response response) throws ResourceException {
        super(context, request, response);
    }
    
    @Override
    public boolean allowPost() {
        return true;
    }
    
    @Override
    public void handlePost() {
        super.handleGet();
    }
    
    private void lookupFromLucene(String terms) throws ResourceException {
        // the Lucene search syntax is _not_ something we want to expose users to, so 
        // quote every term in order to avoid parsing exceptions
        terms = quoteAllTerms(terms);
        
        try {
            // search the results
            Query q = new QueryParser(Version.LUCENE_30, "wkt", new StandardAnalyzer(
                    Version.LUCENE_30)).parse(terms);
            int hitsPerPage = 20;
            TopScoreDocCollector collector = TopScoreDocCollector.create(hitsPerPage, true);
            SEARCHER.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;

            // accumulate them
            List<Map<String, String>> codes = new ArrayList<Map<String, String>>();
            for (int i = 0; i < hits.length; ++i) {
                int docId = hits[i].doc;
                Document d = SEARCHER.doc(docId);
                String code = d.get("code");
                codes.add(asCRSMap(code, CRS.decode("EPSG:" + code)));
            }
            dataModel.put("totalHits", collector.getTotalHits());
            dataModel.put("codes", codes);
        } catch (Exception e) {
            throw new ResourceException(Status.SERVER_ERROR_INTERNAL, e);
        }

    }
    
    /**
     * This code 
     * @param terms
     * @return
     */
    String quoteAllTerms(String terms) {
        // this is high regular expression wizardry coming from Stackoverflow:
        // http://stackoverflow.com/questions/366202/regex-for-splitting-a-string-using-space-when-not-surrounded-by-single-or-double
        
        // tokenize preserving groups contained in double and single quotes 
        List<String> matchList = new ArrayList<String>();
        Pattern regex = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'");
        Matcher regexMatcher = regex.matcher(terms);
        while (regexMatcher.find()) {
            if (regexMatcher.group(1) != null) {
                // Add double-quoted string without the quotes
                matchList.add(regexMatcher.group(1));
            } else if (regexMatcher.group(2) != null) {
                // Add single-quoted string without the quotes
                matchList.add(regexMatcher.group(2));
            } else {
                // Add unquoted word
                matchList.add(regexMatcher.group());
            }
        }
        
        // rebuild as a set of quoted, harmless bits
        StringBuilder result = new StringBuilder();
        for (String s : matchList) {
            if(s != null && !"".equals(s)) {
                result.append("\"" + s + "\" ");
            }
        }
        
        return result.toString().trim();
    }

    /**
     * Tries to lookup as WKT, if that does not work, use a keyword based approach
     * @param terms
     * @throws ResourceException
     */
    private void lookupAuto(String terms) throws ResourceException {
        try {
            // see if parseable
            CRS.parseWKT(terms);
            lookupFromWkt(terms);
        } catch (FactoryException e1) {
            try {
                // see if decodable
                CoordinateReferenceSystem crs = CRS.decode(terms);
                Integer code = CRS.lookupEpsgCode(crs, true);
                if (code != null) {
                    dataModel.put("exact", Boolean.TRUE);
                    dataModel.put("codes", Arrays.asList(asCRSMap(String.valueOf(code), crs)));
                } 
            } catch(FactoryException e2) {
                // failed to parse as WKT. This might also mean the thing is a WKT, but with
                // some parameters GT2 cannot parse. If it is, Lucene won't be happy either with it,
                // so let's check and cleanup a bit the search string.
                
                if(terms.length() > 7) {
                    String start = terms.trim().substring(0, 7).toUpperCase();
                    if(start.startsWith("PROJCS") || start.startsWith("GEOGCS") || start.startsWith("COMPD_CS")) {
                        // remove parenthesis and common terms
                        String cleaned = terms.replaceAll("(COMPD_CS|PROJCS|GEOGCS|DATUM|SPHEROID|TOWGS84|AUTHORITY|PRIMEM|UNIT|AXIS|AUTHORITY|PARAMETER|PROJECTION|VERT_CS|[\\[\\],\\n\\r]+)", " ");
                        terms = cleaned;
                    }
                }
                
                lookupFromLucene(terms);
            }
        }
    }

    /**
     * Truns the CRS into a set of terms suitable for a keyword search
     * @param crs
     * @return
     */
    private String extractTermsFromCRS(CoordinateReferenceSystem crs) {
        StringBuilder sb = new StringBuilder();
        extractTermsFromIdentifiedObject(crs, sb);
        return sb.toString();
    }
    
    private void extractTermsFromIdentifiedObject(IdentifiedObject id, StringBuilder sb) {
        sb.append(id.getName().getCode()).append(" ");
        if(id instanceof CoordinateReferenceSystem) {
            CoordinateReferenceSystem crs = (CoordinateReferenceSystem) id;
            extractTermsFromIdentifiedObject(crs.getCoordinateSystem(), sb);
            if(crs instanceof ProjectedCRS) {
                ProjectedCRS pcrs = (ProjectedCRS) crs;
                extractTermsFromIdentifiedObject(pcrs.getBaseCRS(), sb);
                extractTermsFromIdentifiedObject(pcrs.getConversionFromBase(), sb);
            } else if(crs instanceof CompoundCRS) {
                CompoundCRS ccrs = (CompoundCRS) crs;
                for(CoordinateReferenceSystem child : ccrs.getCoordinateReferenceSystems()) {
                    extractTermsFromIdentifiedObject(child, sb);
                }
            }
        } else if(id instanceof Projection) {
            Projection p = (Projection) id;
            extractTermsFromIdentifiedObject(p.getMethod(), sb);
            ParameterValueGroup params = p.getParameterValues();
            for(GeneralParameterValue gpv : params.values()) {
                extractTermsFromIdentifiedObject(gpv.getDescriptor(), sb);
                if(gpv instanceof ParameterValue) {
                    Object value = ((ParameterValue) gpv).getValue();
                    if(value != null) {
                        sb.append(value).append(" ");
                    } 
                }
            }
        }
    }

    private void lookupFromWkt(String terms) throws ResourceException {
        try {
            CoordinateReferenceSystem crs = CRS.parseWKT(terms);
            Integer code = CRS.lookupEpsgCode(crs, true);
            if (code != null) {
                dataModel.put("exact", Boolean.TRUE);
                dataModel.put("codes", Arrays.asList(asCRSMap(String.valueOf(code), crs)));
            } else {
                // we can parse but we don't get any result -> distill a set of
                // serch terms from the CRS and use Lucene search
                String distilledTerms = extractTermsFromCRS(crs);
                lookupFromLucene(distilledTerms);
            } 
        } catch (FactoryException e) {
            dataModel.put("errors", "Invalid WKT syntax: " + e.getMessage());
        }
    }
    
    Map<String, String> asCRSMap(String code, CoordinateReferenceSystem crs) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("code", code);
        map.put("name", crs.getName().getCode());
        map.put("url", getRequest().getRootRef().toString() + "/" + "epsg/" + code + ".json");
        return map;
    }
   

    @Override
    public Representation represent(Variant variant) throws ResourceException {
        String terms = null;
        String modeKey = null;
        SearchMode mode = SearchMode.auto;
        
        Request request = getRequest();

        // parse the possible different forms, GET, POST and POST with file upload
        if(Method.GET.equals(request.getMethod())) {
            // see if we have to search for a code
            terms = (String) request.getAttributes().get("terms");
            modeKey = (String) request.getAttributes().get("mode");
        } else if(Method.POST.equals(request.getMethod())) {
            if(request.getEntity().getMediaType().equals(MediaType.APPLICATION_WWW_FORM)) {
                Form form = request.getEntityAsForm();
                terms = (String) form.getFirstValue("terms");
                modeKey = (String) form.getFirstValue("mode");
            } else if(request.getEntity().getMediaType().getName().startsWith(MediaType.MULTIPART_FORM_DATA.getName())) {
                DiskFileItemFactory factory = new DiskFileItemFactory();
                factory.setSizeThreshold(PRJ_MAX_SIZE_KB * 1024);

                try {
                    RestletFileUpload upload = new RestletFileUpload(factory);
                    String fileContents = null;
                    for (FileItem item : upload.parseRequest(getRequest())) {
                        if ("mode".equals(item.getFieldName())) {
                            modeKey = item.getString();
                        } else if ("prjfile".equals(item.getFieldName())) {
                            if (item.getSize() > 64 * 1024) {
                                throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST,
                                        "Maximum size for a .prj file is set to " + PRJ_MAX_SIZE_KB + "KB");
                            }
                            fileContents = item.getString();
                        } else if("terms".equals(item.getFieldName())) {
                            terms = item.getString();
                        }
                    }
                    if(fileContents != null && fileContents.length() > 0) {
                        terms = fileContents;
                    }
                } catch (FileUploadException e) {
                    throw new ResourceException(Status.SERVER_ERROR_INTERNAL, e);
                }
            } else {
                throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST);
            }
        } else {
            throw new ResourceException(Status.CLIENT_ERROR_BAD_REQUEST);
        }
        
        // sanitize a bit the search terms
        if(terms != null) {
            terms = terms.trim();
        }
        
        // common values for the data model
        dataModel.put("html_showResults", Boolean.FALSE);
        dataModel.put("html_terms", terms != null ? terms : "");
        dataModel.put("exact", Boolean.FALSE);

        // actually perform the search
        if (modeKey != null) {
            mode = SearchMode.valueOf(modeKey);
        }
        try {
            if (terms != null && !"".equals(terms)) {
                dataModel.put("html_showResults", Boolean.TRUE);
                dataModel.put("codes", Collections.emptyList());
                if(mode == SearchMode.auto) {
                    lookupAuto(terms);
                } else if (mode == SearchMode.wkt) {
                    lookupFromWkt(terms);
                } else if (mode == SearchMode.keywords) {
                    lookupFromLucene(terms);
                }
            }
        } catch(Throwable t) {
            LOGGER.log(Level.SEVERE, "Search failure: " + t.getMessage(), t);
            if(t instanceof ResourceException) {
                throw (ResourceException) t;
            } else {
                throw new ResourceException(Status.SERVER_ERROR_INTERNAL, t.getMessage(), t);
            }
        }
        
        
        return super.represent(variant);
    }
    
}
