#!/bin/bash

zone=$1
band=$2
tile=$3
year=$4
month=$5
day=$6
number=$7

baseurl="http://sentinel-s2-l1c.s3.amazonaws.com/tiles/$zone/$band/$tile/$year/$month/$day/$number/"

urls10="${baseurl}B02.jp2 ${baseurl}B03.jp2 ${baseurl}B04.jp2 ${baseurl}B08.jp2"
urls20="${baseurl}B05.jp2 ${baseurl}B06.jp2 ${baseurl}B07.jp2 ${baseurl}B8A.jp2 ${baseurl}B11.jp2 ${baseurl}B12.jp2"
urls60="${baseurl}B01.jp2 ${baseurl}B09.jp2 ${baseurl}B10.jp2"

echo $urls10 | xargs -n 1 -P 4 wget -q && \
gdal_merge.py -separate -o "$zone$band$tile-$year-$month-$day-$number-10m.tif" B02.jp2 B03.jp2 B04.jp2 B08.jp2 && \
rm B02.jp2 B03.jp2 B04.jp2 B08.jp2 &

#echo $urls60 | xargs -n 1 -P 3 wget -q && \
#gdal_merge.py -separate -o "$zone$band$tile-$year-$month-$day-$number-60m.tif" B01.jp2 B09.jp2 B10.jp2 > /dev/null && \
#rm B01.jp2 B09.jp2 B10.jp2 &

#echo $urls20 | xargs -n 1 -P 6 wget -q && \
#gdal_merge.py -separate -o "$zone$band$tile-$year-$month-$day-$number-20m.tif" B05.jp2 B06.jp2 B07.jp2 B8A.jp2 B11.jp2 B12.jp2 > /dev/null && \
#rm B05.jp2 B06.jp2 B07.jp2 B8A.jp2 B11.jp2 B12.jp2 &

echo "Waiting for conversions to complete"
wait

if [[ ! -z "$8" ]]
then
  mv "$zone$band$tile-$year-$month-$day-$number-10m.tif" "$zone$band$tile-$year-$month-$day-$number-20m.tif" "$zone$band$tile-$year-$month-$day-$number-60m.tif" 
fi
