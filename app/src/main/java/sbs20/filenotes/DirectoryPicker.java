package sbs20.filenotes;

import android.content.Context;
import android.util.AttributeSet;

import sbs20.filenotes.storage.FileSystemService;
import sbs20.filenotes.storage.IDirectoryProvider;

public class DirectoryPicker extends FolderPicker {

	public DirectoryPicker(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

    @Override
    public IDirectoryProvider createProvider() {
        return FileSystemService.getInstance();
    }
}
