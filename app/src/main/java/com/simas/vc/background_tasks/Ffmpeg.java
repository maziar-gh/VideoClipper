/*
 * Copyright (c) 2015. Simas Abramovas
 *
 * This file is part of VideoClipper.
 *
 * VideoClipper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VideoClipper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VideoClipper. If not, see <http://www.gnu.org/licenses/>.
 */
package com.simas.vc.background_tasks;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;
import com.simas.vc.ArgumentBuilder;
import com.simas.vc.Utils;
import com.simas.vc.VC;
import com.simas.vc.VCException;
import com.simas.vc.R;
import com.simas.vc.attributes.VideoStream;
import com.simas.vc.nav_drawer.NavItem;
import java.io.File;
import java.io.IOException;
import java.util.List;

// ToDo rename concat to merge (including the action) or not?
// ToDo VCException use res instead of hardcoded string

/**
 * Contains the convenience methods that might call {@code FfmpegService} to do furhter work via
 * JNI.
 */
public class Ffmpeg {

	private static final String TAG = "ffmpeg";

	// C method prototypes
	public static native Bitmap createPreview(String videoPath);
	static native int cFfmpeg(String[] args);

	/**
	 *
	 * @param outputFile       output file (must already exist)
	 * @param items            items which will be concatenated
	 * @throws IOException An un-recoverable, internal error
	 * @throws VCException An error message to be printed out for the user
	 */
	public static void concat(@NonNull File outputFile, @NonNull List<NavItem> items)
			throws IOException, VCException {
		// Check source count
		if (items.size() < 2) {
			throw new VCException(VC.getStr(R.string.at_least_2_videos));
		}

		// Fetch item validity and calculate total duration
		int duration = 0;
		for (NavItem item : items) {
			if (item.getState() == NavItem.State.VALID) {
				duration += item.getAttributes().getDuration();
			} else {
				throw new VCException("Concatenation cancelled! Some items are invalid or are " +
						"still being processed.");
			}
		}

		// Prepare a tmp file for progress output
		File progressFile = File.createTempFile("vc-pg", null);

		// ToDo check stream counts

		VideoStream stream = null;
		// Loop items
		for (NavItem item : items) {
			// Loop streams
			for (VideoStream videoStream : item.getAttributes().getVideoStreams()) {
				if (stream == null) {
					stream = videoStream;
				} else {
					if (!concatDemuxerFieldsMatch(stream, videoStream)) {
						// VideoStreams have different fields, concat demuxer is not applicable
						// Instead, use a filter
						concatFilter(outputFile, progressFile, items, duration);
						return;
					}
				}
			}
		}

		// VideoStream check passed, use the concat demuxer
		concatDemuxer(outputFile, progressFile, items, duration);
	}

	/**
	 * ToDo Each item's stream count must match or this method will fail.
	 */
	private static void concatDemuxer(@NonNull File outputFile, @NonNull File progressFile,
	                                  @NonNull List<NavItem> items, int duration)
			throws IOException {
		/* Command
			./ffmpeg -y \
			-progress progress \
			-f concat \
			-auto_convert 1 \
			-i inputs \
			-codec copy \
			'output.mp4'
		 */

		// Prepare a tmp file with all video file names
		File tmpFile = File.createTempFile("vc-ls", null);
		String sourceList = "";
		for (NavItem item : items) {
			sourceList += String.format("file '%s'\n", item.getFile().getPath());
		}
		Utils.copyBytes(sourceList.getBytes(), tmpFile);

		// Prepare arguments
		String[] args = new ArgumentBuilder(TAG)
				.add("-y")                                  // Overwrite output file if it exists
				.add("-progress")                           // Output progress to tmp file
				.addSpaced("%s", progressFile.getPath())
				.add("-f concat")                           // Format concat
				.add("-auto_convert 1")                     // Convert packets to make streams concatenable
				.add("-i")                                  // Input files listed in tmpFile
				.addSpaced("%s", tmpFile.getPath())
				.add("-codec copy")                         // Copy source codecs
				.addSpaced("%s", outputFile.getPath())      // Output file
				.build();

		// Call service
		Context context = VC.getAppContext();
		Intent intent = new Intent(context, FfmpegService.class);
		intent.putExtra(FfmpegService.ARG_EXEC_ARGS, args);
		intent.putExtra(FfmpegService.ARG_OUTPUT_FILE, outputFile);
		intent.putExtra(FfmpegService.ARG_PROGRESS_FILE, progressFile);
		intent.putExtra(FfmpegService.ARG_OUTPUT_DURATION, duration);
		context.startService(intent);
	}

	/**
	 * This method expects the first stream is the video and the second one is audio. Ignores
	 * others.
	 */
	private static void concatFilter(@NonNull File outputFile, @NonNull File progressFile,
	                                  @NonNull List<NavItem> items, int duration)
			throws IOException {
		/* Command
			./ffmpeg -y \
			-progress progress \
			-i 'nature/goose.mp4' \
			-i 'nature/bee.mp4' \
			-filter_complex '[0:0] [0:1] [1:0] [1:1]  concat=n=2:v=1:a=1 [v] [a]' \
			-map '[v]' -map '[a]' \
			'output.mp4'
		 */

		int itemCount = items.size();
		String[] sourceList = new String[itemCount*2];
		String streamList = "";
		for (int i=0; i<itemCount; ++i) {
			NavItem item = items.get(i);
			sourceList[i*2] = "-i";
			sourceList[i*2 + 1] = item.getFile().getPath();

			streamList += String.format("[%d:%d] [%d:%d] ", i, 0, i, 1);
			// Loop item streams
//			List<Stream> streams = item.getAttributes().getStreams();
//			int streamCount = streams.size();
//			for (int s=0; s<2; ++i) {
//
//			}
		}

		// ToDo avoid using experimental codecs by using external libs (libfdk_aac is one of them)
		// Prepare arguments
		String[] args = new ArgumentBuilder(TAG)
				.add("-y")                                  // Overwrite output file if it exists
				.add("-strict experimental")                // Use experimental decoders
				.add("-progress")                           // Output progress to tmp file
				.addSpaced("%s", progressFile.getPath())
				.addSpaced(sourceList)                      // List of sources
				// Specific filter (concat)
				.add("-filter_complex")
				.addSpaced("%s concat=n=%d:v=1:a=1 [v] [a]", streamList, itemCount)
				.add("-map [v] -map [a]")
				.add("-strict experimental")                // Use experimental encoders
				.addSpaced("%s", outputFile.getPath())      // Output file
				.build();

		// Call service
		Context context = VC.getAppContext();
		Intent intent = new Intent(context, FfmpegService.class);
		intent.putExtra(FfmpegService.ARG_EXEC_ARGS, args);
		intent.putExtra(FfmpegService.ARG_OUTPUT_FILE, outputFile);
		intent.putExtra(FfmpegService.ARG_PROGRESS_FILE, progressFile);
		intent.putExtra(FfmpegService.ARG_OUTPUT_DURATION, duration);
		context.startService(intent);
	}

	/**
	 * Check if the required fields match for both {@code VideoAttributes}. The required fields are:
	 * <ul>
	 *     <li>
	 *         Width
	 *     </li>
	 *     <li>
	 *         Height
	 *     </li>
	 *     <li>
	 *         Codec tag
	 *     </li>
	 *     <li>
	 *         TBN
	 *     </li>
	 *     <li>
	 *         TBC
	 *     </li>
	 *     <li>
	 *         TBR
	 *     </li>
	 * </ul>
	 * @return true if the required field set matches
	 */
	public static boolean concatDemuxerFieldsMatch(VideoStream va1, VideoStream va2) {
		return Utils.equals(va1.getWidth(), va2.getWidth()) &&
				Utils.equals(va1.getHeight(), va2.getHeight()) &&
				Utils.equals(va1.getCodecTag(), va2.getCodecTag()) &&
				Utils.equals(va1.getTBN(), va2.getTBN()) &&
				Utils.equals(va1.getTBC(), va2.getTBC()) &&
				Utils.equals(va1.getTBR(), va2.getTBR());
	}

}

