﻿// dllmain.cpp : 定义 DLL 应用程序的入口点。
#include "pch.h"
#include <string>
#include <Windows.h>
#include <concurrent_unordered_map.h>
using namespace std;

concurrency::concurrent_unordered_map<string, pair<HANDLE, void*>> connectionPool;

extern "C" {
	_declspec(dllexport) char* getResult(char disk, const char* listName, int priority, int offset);
	_declspec(dllexport) void closeAllSharedMemory();
	_declspec(dllexport) bool isComplete();
}

inline void createFileMapping(HANDLE& hMapFile, LPVOID& pBuf, size_t memorySize, const char* sharedMemoryName);

char* getResult(char disk, const char* listName, const int priority, const int offset)
{
	constexpr int maxPath = 500;
	string memoryName("sharedMemory:");
	memoryName += disk;
	memoryName += ":";
	memoryName += listName;
	memoryName += ":";
	memoryName += to_string(priority);
	const string resultMemoryName(memoryName);
	memoryName += "size";
	const string resultSizeMemoryName(memoryName);
	HANDLE hMapFile;
	void* resultsSize = nullptr;
	if (connectionPool.find(resultSizeMemoryName) == connectionPool.end())
	{
		createFileMapping(hMapFile, resultsSize, sizeof size_t, resultSizeMemoryName.c_str());
	}
	else
	{
		resultsSize = static_cast<int*>(connectionPool.at(resultSizeMemoryName).second);
	}
	if (resultsSize == nullptr)
	{
		return nullptr;
	}
	const int resultCount = *static_cast<int*>(resultsSize) / maxPath;
	if (resultCount < offset)
	{
		return nullptr;
	}
	void* resultsPtr;
	if (connectionPool.find(resultMemoryName) == connectionPool.end())
	{
		createFileMapping(hMapFile, resultsPtr, *static_cast<int*>(resultsSize), resultMemoryName.c_str());
	}
	else
	{
		resultsPtr = connectionPool.at(resultMemoryName).second;
	}
	return (char*)(reinterpret_cast<long long>(resultsPtr) + static_cast<long long>(offset) * maxPath);
}

inline void createFileMapping(HANDLE& hMapFile, LPVOID& pBuf, size_t memorySize, const char* sharedMemoryName)
{
	// 创建共享文件句柄
	hMapFile = CreateFileMappingA(
		INVALID_HANDLE_VALUE, // 物理文件句柄
		nullptr, // 默认安全级别
		PAGE_READWRITE, // 可读可写
		0, // 高位文件大小
		memorySize, // 低位文件大小
		sharedMemoryName
	);

	pBuf = MapViewOfFile(
		hMapFile, // 共享内存的句柄
		FILE_MAP_ALL_ACCESS, // 可读写许可
		0,
		0,
		memorySize
	);
	connectionPool.insert(pair<string, pair<HANDLE, void*>>(sharedMemoryName, pair<HANDLE, void*>(hMapFile, pBuf)));
}

bool isComplete()
{
	void* pBuf;
	const char* completeSignal = "sharedMemory:complete:status";
	if (connectionPool.find(completeSignal) == connectionPool.end())
	{
		HANDLE hMapFile;
		createFileMapping(hMapFile, pBuf, sizeof(bool), completeSignal);
	}
	else
	{
		pBuf = connectionPool.at(completeSignal).second;
	}
	if (pBuf == nullptr)
	{
		return false;
	}
	return *static_cast<bool*>(pBuf);
}

void closeAllSharedMemory()
{
	for (const auto& each : connectionPool)
	{
		UnmapViewOfFile(each.second.second);
		CloseHandle(each.second.first);
	}
}