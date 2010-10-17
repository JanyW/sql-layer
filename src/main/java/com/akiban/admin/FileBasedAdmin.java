package com.akiban.admin;

import com.akiban.admin.state.ChunkserverState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class FileBasedAdmin extends Admin
{
    // Admin interface

    @Override
    public boolean real()
    {
        return false;
    }

    public boolean set(final String adminKey, final Integer version, final String value)
    {
        return new Action<Boolean>("set", adminKey)
        {
            @Override
            protected Boolean action() throws InterruptedException
            {
                boolean updated = false;
                throw new Admin.RuntimeException("Not implemented yet");
            }
        }.run();
    }

    public boolean delete(final String adminKey, final Integer version)
        throws StaleUpdateException
    {
        return new Action<Boolean>("delete", adminKey)
        {
            @Override
            protected Boolean action() throws Exception
            {
                boolean updated;
                throw new Admin.RuntimeException("Not implemented yet");
            }
        }.run();
    }

    public boolean deleteDirectory(final String adminKey)
        throws StaleUpdateException
    {
        return new Action<Boolean>("deleteDirectory", adminKey)
        {
            @Override
            protected Boolean action() throws Exception
            {
                boolean updated;
                throw new Admin.RuntimeException("Not implemented yet");
            }
        }.run();
    }

    public AdminValue get(final String adminKey)
    {
        checkKey(adminKey);
        return new Action<AdminValue>("get", adminKey)
        {
            @Override
            protected AdminValue action() throws InterruptedException
            {
                try {
                    return new AdminValue(adminKey, 0, fileContents(adminKey));
                } catch (IOException e) {
                    String message = String.format("Caught IOException while reading %s", adminKey);
                    logger.error(message, e);
                    throw new RuntimeException(message, e);
                }
            }
        }.run();
    }

    public void register(final String adminKey, final Handler handler)
    {
        if (logger.isInfoEnabled()) {
            logger.warn(String.format("Cannot register handler for %s. Registration is disabled for file-based admin",
                                      adminKey));
        }
        checkKey(adminKey);
    }

    public void unregister(final String adminKey, final Handler handler)
    {
        if (logger.isInfoEnabled()) {
            logger.warn(String.format("Cannot unregister handler for %s. Registration is disabled for file-based admin",
                                      adminKey));
        }
        checkKey(adminKey);
    }

    public void markChunkserverUp(String chunkserverName) throws StaleUpdateException
    {
        logger.warn(String.format("Fake admin -- can't mark %s as up", chunkserverName));
    }

    public void markChunkserverDown(String chunkserverName) throws StaleUpdateException
    {
        logger.warn(String.format("Fake admin -- can't mark %s as down", chunkserverName));
    }

    protected boolean ensurePathExists(String leafPath, byte[] leafValue) throws InterruptedException
    {
        return file(leafPath).exists();
    }

    // For use by this package

    FileBasedAdmin(String adminRootPath) throws IOException
    {
        super(adminRootPath);
        adminRoot = new File(adminRootPath);
        if (!adminRoot.exists() || !adminRoot.isDirectory()) {
            throw new RuntimeException
                (String.format("Admin root directory %s does not exist or is not really a directory", adminRoot));
        }
        logger.info(String.format("Started file-based admin using config at %s", adminRootPath));
    }

    // For use by this class

    private byte[] fileContents(String adminKey) throws IOException
    {
        try {
            File file = file(adminKey);
            FileInputStream input = new FileInputStream(file);
            FileChannel channel = input.getChannel();
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            channel.read(buffer);
            return buffer.array();
        } catch (FileNotFoundException e) {
            // Shouldn't happen since we already checked the key
            logger.error(adminKey, e);
            return null;
        }
    }

    private File file(String adminKey)
    {
        // adminKey should already have been checked.
        return new File(adminRoot, adminKey.substring(1));
    }

    // State

    private final File adminRoot;
}