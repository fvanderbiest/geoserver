/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wcs2_0.util;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.media.jai.Interpolation;

import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.catalog.CoverageStoreInfo;
import org.geoserver.data.util.CoverageUtils;
import org.geoserver.platform.OWS20Exception;
import org.geoserver.platform.ServiceException;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.factory.GeoTools;
import org.geotools.factory.Hints;
import org.geotools.gce.imagemosaic.ImageMosaicFormat;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.parameter.Parameter;
import org.geotools.referencing.CRS;
import org.geotools.util.DefaultProgressListener;
import org.geotools.util.Version;
import org.geotools.util.logging.Logging;
import org.opengis.coverage.grid.Format;
import org.opengis.coverage.grid.GridCoverageReader;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.coverage.grid.GridGeometry;
import org.opengis.geometry.BoundingBox;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterDescriptor;
import org.opengis.parameter.ParameterValue;
import org.opengis.parameter.ParameterValueGroup;
import org.opengis.referencing.datum.PixelInCell;
import org.opengis.referencing.operation.MathTransform;


/**
 * Utility class performing operations related to http requests.
 *
 *
 * TODO: these methods should be put back into org.geoserver.ows.util.RequestUtils
 */
public class RequestUtils {
    
    /** {@link Logger} for this class.*/
    private final static Logger LOGGER= Logging.getLogger(RequestUtils.class);

    /**
     * Given a list of provided versions, and a list of accepted versions, this method will
     * return the negotiated version to be used for response according to the OWS 2.0 specification.
     *
     * The difference from the 11 version is that here versions can have format "x.y".
     *
     * @param providedList a non null, non empty list of provided versions (in "x.y.z" or "x.y" format)
     * @param acceptedList a list of accepted versions, eventually null or empty (in "x.y.z" or "x.y" format)
     * @return the negotiated version to be used for response
     *
     * @see org.geoserver.ows.util.RequestUtils#getVersionOws11(java.util.List, java.util.List) 
     */
    public static String getVersionOws20(List<String> providedList, List<String> acceptedList) {

        //first figure out which versions are provided
        TreeSet<Version> provided = new TreeSet<Version>();
        for (String v : providedList) {
            provided.add(new Version(v));
        }

        // if no accept list provided, we return the biggest supported version
        if(acceptedList == null || acceptedList.isEmpty())
            return provided.last().toString();


        // next figure out what the client accepts (and check they are good version numbers)
        List<Version> accepted = new ArrayList<Version>();
        for (String v : acceptedList) {
            checkVersionNumber20(v, "AcceptVersions");

            accepted.add(new Version(v));
        }

        // from the specification "The server, upon receiving a GetCapabilities request, shall scan
        // through this list and find the first version number that it supports"
        Version negotiated = null;
        for (Version version : accepted) {
            if (provided.contains(version)) {
                negotiated = version;
                break;
            }
        }

        // from the spec: "If the list does not contain any version numbers that the server
        // supports, the server shall return an Exception with
        // exceptionCode="VersionNegotiationFailed"
        if(negotiated == null)
            throw new OWS20Exception("Could not find any matching version", OWS20Exception.OWSExceptionCode.VersionNegotiationFailed);

        return negotiated.toString();
    }

    /**
     * Checks the validity of a version number (the specification version numbers, two or three dot
     * separated integers between 0 and 99). Throws a ServiceException if the version number
     * is not valid.
     * @param v the version number (in string format)
     * @param the locator for the service exception (may be null)
     */
    public static void checkVersionNumber20(String v, String locator) throws ServiceException {
        if (!v.matches("[0-9]{1,2}\\.[0-9]{1,2}(\\.[0-9]{1,2})?")) {
            String msg = v + " is an invalid version number";
            throw new OWS20Exception("Could not find any matching version,"+msg, OWS20Exception.OWSExceptionCode.VersionNegotiationFailed, locator);
        }
    }

    /**
     * Reads the best matching grid out of a grid coverage applying sub-sampling and using overviews
     * as necessary
     * 
     * @param mapContent
     * @param reader
     * @param params
     * @param readGG
     * @param interpolation
     * @param hints 
     * @return
     * @throws IOException
     */
    public static GridCoverage2D readBestCoverage(
            final GridCoverage2DReader reader, 
            final Object params,
            final GridGeometry2D readGG,
            final Interpolation interpolation, 
            Hints hints) throws IOException {
    
        ////
        //
        // Intersect the present envelope with the request envelope, also in WGS 84 to make sure
        // there is an actual intersection
        //
        ////
        try {
            final ReferencedEnvelope coverageEnvelope=new ReferencedEnvelope(reader.getOriginalEnvelope());
            if (!coverageEnvelope.intersects((BoundingBox) ReferencedEnvelope.reference(readGG.getEnvelope()))) {
                return null;
            }

        } catch (Exception e) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to compare data and request envelopes, proceeding with rendering anyways",
                    e);
        }
    
        // //
        // It is an GridCoverage2DReader, let's use parameters
        // if we have any supplied by a user.
        // //
        // first I created the correct ReadGeometry
        final Parameter<GridGeometry2D> readGGParam = (Parameter<GridGeometry2D>) AbstractGridFormat.READ_GRIDGEOMETRY2D.createValue();
        readGGParam.setValue(readGG);
        
        final Parameter<Interpolation> readInterpolation=(Parameter<Interpolation>) ImageMosaicFormat.INTERPOLATION.createValue(); 
        readInterpolation.setValue(interpolation);
       
        
        // then I try to get read parameters associated with this
        // coverage if there are any.
        GridCoverage2D coverage = null;
        GeneralParameterValue[] readParams = (GeneralParameterValue[]) params;
        final int length = readParams == null ? 0 :readParams.length;
        if (length > 0) {
            // //
            //
            // Getting parameters to control how to read this coverage.
            // Remember to check to actually have them before forwarding
            // them to the reader.
            //
            // //
        
            // we have a valid number of parameters, let's check if
            // also have a READ_GRIDGEOMETRY2D. In such case we just
            // override it with the one we just build for this
            // request.
            final String readGGName = AbstractGridFormat.READ_GRIDGEOMETRY2D.getName().toString();
            final String readInterpolationName = ImageMosaicFormat.INTERPOLATION.getName().toString();
            int i = 0;
            boolean foundInterpolation = false;
            boolean foundGG = false;
            for (; i < length; i++) {
                final String paramName = readParams[i].getDescriptor().getName().toString();
                if (paramName.equalsIgnoreCase(readGGName)){
                    ((Parameter) readParams[i]).setValue(readGG);
                    foundGG = true;
                } else if(paramName.equalsIgnoreCase(readInterpolationName)){
                    ((Parameter) readParams[i]).setValue(interpolation);
                    foundInterpolation = true;
                }
            }
            
            // did we find anything?
            if (!foundGG || !foundInterpolation){// || !(foundBgColor && bgColor != null)) {
                // add the correct read geometry to the supplied
                // params since we did not find anything
                List<GeneralParameterValue> paramList = new ArrayList<GeneralParameterValue>();
                paramList.addAll(Arrays.asList(readParams));
                if(!foundGG) {
                     paramList.add(readGGParam);
                } 
                if(!foundInterpolation) {
                    paramList.add(readInterpolation);
                } 

                readParams = (GeneralParameterValue[]) paramList.toArray(new GeneralParameterValue[paramList.size()]);
            }
            coverage = (GridCoverage2D) reader.read(readParams);
        } else { 
            coverage = (GridCoverage2D) reader.read(new GeneralParameterValue[] {readGGParam ,readInterpolation});
        }
    
        return coverage;
    }

    /**
     * Returns the "Sample to geophysics" transform as an affine transform, or {@code null}
     * if none. Note that the returned instance may be an immutable one, not necessarly the
     * default Java2D implementation.
     *
     * @param  gridToCRS The "grid to CRS" {@link MathTransform} transform.
     * @return The "grid to CRS" affine transform of the given coverage, or {@code null}
     *         if none or if the transform is not affine.
     */
    public static AffineTransform getAffineTransform(final MathTransform gridToCRS) {
        if(gridToCRS==null){
            return null;
        }

        if (gridToCRS instanceof AffineTransform) {
            return (AffineTransform) gridToCRS;
        }
        return null;
    }
    /**
    * Replace or add the provided parameter in the read parameters
    */
    public static <T> GeneralParameterValue[] replaceParameter(
            GeneralParameterValue[] readParameters, Object value, ParameterDescriptor<T> pd) {

        // scan all the params looking for the one we want to add
        for (GeneralParameterValue gpv : readParameters) {
            // in case of match of any alias add a param value to the lot
            if (gpv.getDescriptor().getName().equals(pd.getName())) {
                ((ParameterValue) gpv).setValue(value);
                // leave
                return readParameters;
            }
        }

        // add it to the array
        // add to the list
        GeneralParameterValue[] readParametersClone = new GeneralParameterValue[readParameters.length + 1];
        System.arraycopy(readParameters, 0, readParametersClone, 0, readParameters.length);
        final ParameterValue<T> pv = pd.createValue();
        pv.setValue(value);
        readParametersClone[readParameters.length] = pv;
        return readParametersClone;
    }
    
    /**
     * This utility method can be used to read a small sample {@link GridCoverage2D} for inspection 
     * from the specified {@link CoverageInfo}.
     * 
     * @param ci the {@link CoverageInfo} that contains the description of the GeoServer coverage to read from.
     * @return
     */
    static public GridCoverage2D readSampleGridCoverage(CoverageInfo ci)throws Exception {
        final GridCoverage2DReader reader = getCoverageReader(ci);
        
        //
        // Now reading a fake small GridCoverage just to retrieve meta
        // information about bands:
        //
        // - calculating a new envelope which is just 5x5 pixels
        // - if it's a mosaic, limit the number of tiles we're going to read to one 
        //   (with time and elevation there might be hundreds of superimposed tiles)
        // - reading the GridCoverage subset
        //

        final GeneralEnvelope originalEnvelope = reader.getOriginalEnvelope();
        final GridEnvelope originalRange = reader.getOriginalGridRange();            
        final Format coverageFormat = reader.getFormat();

        final ParameterValueGroup readParams = coverageFormat.getReadParameters();
        final Map parameters = CoverageUtils.getParametersKVP(readParams);
        final int minX = originalRange.getLow(0);
        final int minY = originalRange.getLow(1);
        final int width = originalRange.getSpan(0);
        final int height = originalRange.getSpan(1);
        final int maxX = minX + (width <= 5 ? width : 5);
        final int maxY = minY + (height <= 5 ? height : 5);

        // we have to be sure that we are working against a valid grid range.
        final GridEnvelope2D testRange = new GridEnvelope2D(minX, minY, maxX, maxY);

        // build the corresponding envelope
        final MathTransform gridToWorldCorner = reader.getOriginalGridToWorld(PixelInCell.CELL_CORNER);
        final GeneralEnvelope testEnvelope = CRS.transform(gridToWorldCorner, new GeneralEnvelope(testRange.getBounds()));
        testEnvelope.setCoordinateReferenceSystem(originalEnvelope.getCoordinateReferenceSystem());

        
        // make sure mosaics with many superimposed tiles won't blow up with 
        // a "too many open files" exception
        String maxAllowedTiles = ImageMosaicFormat.MAX_ALLOWED_TILES.getName().toString();
        if(parameters.keySet().contains(maxAllowedTiles)) {
            parameters.put(maxAllowedTiles, 1);
        }
        parameters.put(AbstractGridFormat.READ_GRIDGEOMETRY2D.getName().toString(), new GridGeometry2D(testRange, testEnvelope));

        // try to read this coverage
        return (GridCoverage2D) reader.read(CoverageUtils.getParameters(readParams, parameters, true));          
    }

    /**
     * Grabs the reader from the specified coverage
     * @param ci
     * @return
     * @throws IOException
     * @throws Exception
     */
    public static GridCoverage2DReader getCoverageReader(CoverageInfo ci)
            throws IOException, Exception {
        // get a reader for this coverage
        final CoverageStoreInfo store = (CoverageStoreInfo) ci.getStore();
        final GridCoverageReader reader_ = ci.getGridCoverageReader(new DefaultProgressListener(), GeoTools.getDefaultHints());
        if (reader_ == null) {
            throw new Exception("Unable to acquire a reader for this coverage with format: "
                    + store.getFormat().getName());
        }
        final GridCoverage2DReader reader = (GridCoverage2DReader) reader_;
        return reader;
    }
}
